// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package build.buildfarm.server;

import static java.lang.String.format;

import build.bazel.remote.execution.v2.ExecuteRequest;
import build.bazel.remote.execution.v2.ExecutionGrpc;
import build.bazel.remote.execution.v2.WaitExecutionRequest;
import build.buildfarm.common.DigestUtil;
import build.buildfarm.common.grpc.TracingMetadataUtils;
import build.buildfarm.instance.Instance;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.longrunning.Operation;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Logger;

public class ExecutionService extends ExecutionGrpc.ExecutionImplBase {
  public static final Logger logger = Logger.getLogger(ExecutionService.class.getName());

  private final Instances instances;

  public ExecutionService(Instances instances) {
    this.instances = instances;
  }

  private void logExecute(String instanceName, ExecuteRequest request) {
    logger.info(format("ExecutionSuccess: %s: %s", instanceName, DigestUtil.toString(request.getActionDigest())));
  }

  private Predicate<Operation> createWatcher(StreamObserver<Operation> responseObserver) {
    return (operation) -> {
      if (Context.current().isCancelled()) {
        return false;
      }
      try {
        if (operation == null) {
          responseObserver.onError(Status.NOT_FOUND.asException());
          return false;
        }
        responseObserver.onNext(operation);
        if (operation.getDone()) {
          responseObserver.onCompleted();
          return false;
        }
      } catch (StatusRuntimeException e) {
        // no further responses should be necessary
        if (e.getStatus().getCode() != Status.Code.CANCELLED) {
          responseObserver.onError(Status.fromThrowable(e).asException());
        }
        return false;
      } catch (IllegalStateException e) {
        // only indicator for this from ServerCallImpl layer
        // no further responses should be necessary
        if (!e.getMessage().equals("call is closed")) {
          responseObserver.onError(Status.fromThrowable(e).asException());
        }
        return false;
      }
      // still watching
      return true;
    };
  }

  @Override
  public void waitExecution(
      WaitExecutionRequest request, StreamObserver<Operation> responseObserver) {
    String operationName = request.getName();
    Instance instance;
    try {
      instance = instances.getFromOperationName(operationName);
    } catch (InstanceNotFoundException e) {
      responseObserver.onError(BuildFarmInstances.toStatusException(e));
      return;
    }

    boolean watching = instance.watchOperation(
        operationName,
        createWatcher(responseObserver));
    if (!watching) {
      responseObserver.onCompleted();
    }
  }

  @Override
  public void execute(
      ExecuteRequest request, StreamObserver<Operation> responseObserver) {
    Instance instance;
    try {
      instance = instances.get(request.getInstanceName());
    } catch (InstanceNotFoundException e) {
      responseObserver.onError(BuildFarmInstances.toStatusException(e));
      return;
    }

    logExecute(instance.getName(), request);

    try {
      instance.execute(
          request.getActionDigest(),
          request.getSkipCacheLookup(),
          request.getExecutionPolicy(),
          request.getResultsCachePolicy(),
          TracingMetadataUtils.fromCurrentContext(),
          createWatcher(responseObserver));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
