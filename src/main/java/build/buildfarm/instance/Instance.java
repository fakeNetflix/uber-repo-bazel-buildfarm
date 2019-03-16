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

package build.buildfarm.instance;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.ExecutionPolicy;
import build.bazel.remote.execution.v2.ResultsCachePolicy;
import build.bazel.remote.execution.v2.ExecuteOperationMetadata.Stage;
import build.bazel.remote.execution.v2.Platform;
import build.bazel.remote.execution.v2.RequestMetadata;
import build.bazel.remote.execution.v2.ServerCapabilities;
import build.buildfarm.common.DigestUtil;
import build.buildfarm.common.DigestUtil.ActionKey;
import build.buildfarm.common.function.InterruptingPredicate;
import build.buildfarm.v1test.QueueEntry;
import build.buildfarm.v1test.QueuedOperation;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.longrunning.Operation;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;

public interface Instance {
  String getName();

  DigestUtil getDigestUtil();

  void start();
  void stop() throws InterruptedException;

  ActionResult getActionResult(ActionKey actionKey);
  void putActionResult(ActionKey actionKey, ActionResult actionResult) throws InterruptedException;

  ListenableFuture<Iterable<Digest>> findMissingBlobs(Iterable<Digest> digests, ExecutorService service);

  String getBlobName(Digest blobDigest);
  void getBlob(Digest blobDigest, long offset, long limit, StreamObserver<ByteString> blobObserver);
  ChunkObserver getWriteBlobObserver(Digest blobDigest);
  ChunkObserver getWriteOperationStreamObserver(String operationStream);
  String getTree(
      Digest rootDigest,
      int pageSize,
      String pageToken,
      ImmutableList.Builder<Directory> directories,
      boolean acceptMissing) throws IOException, InterruptedException;
  CommittingOutputStream getStreamOutput(String name, long expectedSize);
  InputStream newStreamInput(String name, long offset) throws IOException, InterruptedException;

  void execute(
      Digest actionDigest,
      boolean skipCacheLookup,
      ExecutionPolicy executionPolicy,
      ResultsCachePolicy resultsCachePolicy,
      RequestMetadata requestMetadata,
      Predicate<Operation> onOperation) throws InterruptedException;
  void match(Platform platform, MatchListener listener) throws InterruptedException;
  boolean putOperation(Operation operation) throws InterruptedException;
  boolean putAndValidateOperation(Operation operation) throws InterruptedException;
  boolean pollOperation(String operationName, Stage stage);
  // returns nextPageToken suitable for list restart
  String listOperations(
      int pageSize,
      String pageToken,
      String filter,
      ImmutableList.Builder<Operation> operations);
  Operation getOperation(String name);
  void cancelOperation(String name) throws InterruptedException;
  void deleteOperation(String name);

  // returns true if the operation will be handled in all cases through the
  // watcher.
  // The watcher returns true to indicate it is still able to process updates,
  // and returns false when it is complete and no longer wants updates
  // The watcher must not be tested again after it has returned false.
  boolean watchOperation(
      String operationName,
      Predicate<Operation> watcher);

  ServerCapabilities getCapabilities();

  interface MatchListener {
    // start/end pair called for each wait period
    void onWaitStart();

    void onWaitEnd();

    // returns false if this listener will not handle this match
    boolean onEntry(QueueEntry queueEntry);

    // returns false if this listener will not handle this match
    boolean onOperation(QueuedOperation operation) throws InterruptedException;
  }

  public static interface ChunkObserver extends StreamObserver<ByteString> {
    long getCommittedSize();

    void reset();

    ListenableFuture<Long> getCommittedFuture();
  }

  public static abstract class CommittingOutputStream extends OutputStream {
    public abstract ListenableFuture<Long> getCommittedFuture();
  }
}
