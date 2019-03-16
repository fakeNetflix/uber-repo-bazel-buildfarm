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

import build.buildfarm.instance.Instance;
import build.buildfarm.instance.Instance.SimpleMatchListener;
import build.buildfarm.common.function.InterruptingPredicate;
import build.buildfarm.v1test.OperationQueueGrpc;
import build.buildfarm.v1test.PollOperationRequest;
import build.buildfarm.v1test.TakeOperationRequest;
import build.bazel.remote.execution.v2.ExecuteOperationMetadata;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.Code;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

public class OperationQueueService extends OperationQueueGrpc.OperationQueueImplBase {
  private final Instances instances;

  public OperationQueueService(Instances instances) {
    this.instances = instances;
  }

  private InterruptingPredicate<Operation> createOnMatch(
      Instance instance, StreamObserver<Operation> responseObserver) {
    return (operation) -> {
      // so this is interesting - the stdout injection belongs here, because
      // we use this criteria to select the format for stream/blob differentiation
      try {
        ExecuteOperationMetadata metadata =
            operation.getMetadata().unpack(ExecuteOperationMetadata.class);
        metadata = metadata.toBuilder()
            .setStdoutStreamName(operation.getName() + "/streams/stdout")
            .setStderrStreamName(operation.getName() + "/streams/stderr")
            .build();
        Operation streamableOperation = operation.toBuilder()
            .setMetadata(Any.pack(metadata))
            .build();

        responseObserver.onNext(streamableOperation);
        responseObserver.onCompleted();
        return true;
      } catch(InvalidProtocolBufferException ex) {
        responseObserver.onError(Status.INTERNAL.asException());
        // should we update operation?
      } catch(StatusRuntimeException ex) {
        Status status = Status.fromThrowable(ex);
        if (status.getCode() != Status.Code.CANCELLED) {
          responseObserver.onError(ex);
        }
      }
      try {
        instance.putOperation(operation);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return false;
    };
  }

  @Override
  public void take(
      TakeOperationRequest request,
      StreamObserver<Operation> responseObserver) {
    Instance instance;
    try {
      instance = instances.get(request.getInstanceName());
    } catch (InstanceNotFoundException ex) {
      responseObserver.onError(BuildFarmInstances.toStatusException(ex));
      return;
    }

    try {
      instance.match(
          request.getPlatform(),
          new SimpleMatchListener(createOnMatch(instance, responseObserver)));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void put(
      Operation operation,
      StreamObserver<com.google.rpc.Status> responseObserver) {
    Instance instance;
    try {
      instance = instances.getFromOperationName(operation.getName());
    } catch (InstanceNotFoundException e) {
      responseObserver.onError(BuildFarmInstances.toStatusException(e));
      return;
    }

    try {
      boolean ok = instance.putAndValidateOperation(operation);
      Code code = ok ? Code.OK : Code.UNAVAILABLE;
      responseObserver.onNext(com.google.rpc.Status.newBuilder()
          .setCode(code.getNumber())
          .build());
      responseObserver.onCompleted();
    } catch (IllegalStateException e) {
      responseObserver.onNext(com.google.rpc.Status.newBuilder()
          .setCode(Code.FAILED_PRECONDITION.getNumber())
          .build());
      responseObserver.onCompleted();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void poll(
      PollOperationRequest request,
      StreamObserver<com.google.rpc.Status> responseObserver) {
    Instance instance;
    try {
      instance = instances.getFromOperationName(
          request.getOperationName());
    } catch (InstanceNotFoundException e) {
      responseObserver.onError(BuildFarmInstances.toStatusException(e));
      return;
    }

    boolean ok = instance.pollOperation(
        request.getOperationName(),
        request.getStage());
    Code code = ok ? Code.OK : Code.UNAVAILABLE;
    responseObserver.onNext(com.google.rpc.Status.newBuilder()
        .setCode(code.getNumber())
        .build());
    responseObserver.onCompleted();
  }
}
