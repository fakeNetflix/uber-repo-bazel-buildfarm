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

package build.buildfarm.worker;

import build.buildfarm.common.DigestUtil;
import build.buildfarm.v1test.QueuedOperationMetadata;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.remoteexecution.v1test.Action;
import com.google.devtools.remoteexecution.v1test.Command;
import com.google.devtools.remoteexecution.v1test.Digest;
import com.google.devtools.remoteexecution.v1test.Directory;
import com.google.devtools.remoteexecution.v1test.ExecuteOperationMetadata;
import com.google.devtools.remoteexecution.v1test.Platform;
import com.google.longrunning.Operation;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class MatchStage extends PipelineStage {
  public MatchStage(WorkerContext workerContext, PipelineStage output, PipelineStage error) {
    super("MatchStage", workerContext, output, error);
  }

  @Override
  protected void iterate() throws InterruptedException {
    long startTime = System.nanoTime();
    if (!output.claim()) {
      return;
    }
    long waitTime = System.nanoTime() - startTime;

    workerContext.logInfo("MatchStage: Matching");

    workerContext.match((operation) -> {
      if (operation == null) {
        output.release();
        return false;
      }

      workerContext.logInfo("MatchStage: Starting operation: " + operation.getName());

      try {
        OperationContext context = fetch(operation);
        if (context != null) {
          workerContext.logInfo("MatchStage: Done with operation: " + operation.getName());
        } else {
          workerContext.logInfo("MatchStage: Operation fetch failed: " + operation.getName());
          output.release();
          workerContext.requeue(operation);
        }
        if (context != null) {
          output.put(context);
        }
        return context != null;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    });
    long endTime = System.nanoTime();
    workerContext.logInfo(String.format(
        "MatchStage::iterate(): %gms (%gms wait)",
        (endTime - startTime) / 1000000.0f,
        waitTime / 1000000.0f));
  }

  private static Map<Digest, Directory> createDirectoriesIndex(Iterable<Directory> directories, DigestUtil digestUtil) {
    Set<Digest> directoryDigests = new HashSet<>();
    ImmutableMap.Builder<Digest, Directory> directoriesIndex = new ImmutableMap.Builder<>();
    for (Directory directory : directories) {
      // double compute here...
      Digest directoryDigest = digestUtil.compute(directory);
      if (!directoryDigests.add(directoryDigest)) {
        continue;
      }
      directoriesIndex.put(directoryDigest, directory);
    }

    return directoriesIndex.build();
  }

  private OperationContext fetch(Operation operation) throws InterruptedException {
    if (!operation.getMetadata().is(QueuedOperationMetadata.class)) {
      return null;
    }

    QueuedOperationMetadata metadata;
    try {
      metadata = operation.getMetadata().unpack(QueuedOperationMetadata.class);
    } catch (InvalidProtocolBufferException e) {
      e.printStackTrace();
      return null;
    }

    Action action = metadata.getAction();

    if (action.hasTimeout() && workerContext.hasMaximumActionTimeout()) {
      Duration timeout = action.getTimeout();
      Duration maximum = workerContext.getMaximumActionTimeout();
      if (timeout.getSeconds() > maximum.getSeconds() ||
          (timeout.getSeconds() == maximum.getSeconds() && timeout.getNanos() > maximum.getNanos())) {
        return null;
      }
    }

    Command command = metadata.getCommand();
    if (command.getArgumentsList().isEmpty()) {
      return null;
    }

    Path execDir = workerContext.getRoot().resolve(operation.getName());
    return OperationContext.newBuilder()
        .setOperation(operation)
        .setExecDir(execDir)
        .setDirectoriesIndex(createDirectoriesIndex(metadata.getDirectoriesList(), workerContext.getDigestUtil()))
        .setMetadata(metadata.getExecuteOperationMetadata())
        .setAction(action)
        .setCommand(command)
        .build();
  }

  @Override
  public OperationContext take() throws InterruptedException {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean claim() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void release() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void put(OperationContext operation) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setInput(PipelineStage input) {
    throw new UnsupportedOperationException();
  }
}
