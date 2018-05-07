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

package build.buildfarm.worker.shard;

import build.buildfarm.common.ContentAddressableStorage;
import build.buildfarm.common.ContentAddressableStorage.Blob;
import build.buildfarm.common.DigestUtil;
import build.buildfarm.common.DigestUtil.ActionKey;
import build.buildfarm.common.ShardBackplane;
import build.buildfarm.instance.AbstractServerInstance;
import build.buildfarm.instance.GetDirectoryFunction;
import build.buildfarm.instance.TokenizableIterator;
import build.buildfarm.instance.TreeIterator;
import build.buildfarm.v1test.CompletedOperationMetadata;
import build.buildfarm.v1test.QueuedOperationMetadata;
import build.buildfarm.v1test.ShardWorkerInstanceConfig;
import build.buildfarm.worker.Fetcher;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.remoteexecution.v1test.Action;
import com.google.devtools.remoteexecution.v1test.ActionResult;
import com.google.devtools.remoteexecution.v1test.Digest;
import com.google.devtools.remoteexecution.v1test.Directory;
import com.google.devtools.remoteexecution.v1test.Platform;
import com.google.devtools.remoteexecution.v1test.ExecuteOperationMetadata;
import com.google.devtools.remoteexecution.v1test.ExecuteOperationMetadata.Stage;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.naming.ConfigurationException;

public class ShardWorkerInstance extends AbstractServerInstance {
  private final ShardWorkerInstanceConfig config;
  private final ShardBackplane backplane;
  private final Fetcher fetcher;
  private final ContentAddressableStorage contentAddressableStorage;

  public ShardWorkerInstance(
      String name,
      DigestUtil digestUtil,
      ShardBackplane backplane,
      Fetcher fetcher,
      ContentAddressableStorage contentAddressableStorage,
      ShardWorkerInstanceConfig config) throws ConfigurationException {
    super(name, digestUtil, null, null, null, null);
    this.config = config;
    this.backplane = backplane;
    this.fetcher = fetcher;
    this.contentAddressableStorage = contentAddressableStorage;
  }

  @Override
  public ActionResult getActionResult(ActionKey actionKey) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void putActionResult(ActionKey actionKey, ActionResult actionResult) {
    try {
      backplane.putActionResult(actionKey, actionResult);
    } catch (IOException e) {
      throw Status.fromThrowable(e).asRuntimeException();
    }
  }

  @Override
  public Iterable<Digest> findMissingBlobs(Iterable<Digest> digests) {
    ImmutableList.Builder<Digest> builder = new ImmutableList.Builder<>();
    for (Digest digest : digests) {
      if (!contentAddressableStorage.contains(digest)) {
        builder.add(digest);
      }
    }
    return builder.build();
  }

  @Override
  protected TokenizableIterator<Operation> createOperationsIterator(String pageToken) { throw new UnsupportedOperationException(); }

  @Override
  protected int getListOperationsDefaultPageSize() { return 1024; }

  @Override
  protected int getListOperationsMaxPageSize() { return 1024; }

  @Override
  public Iterable<Digest> putAllBlobs(Iterable<ByteString> blobs)
      throws IOException, IllegalArgumentException, InterruptedException {
    ImmutableList.Builder<Digest> digests = new ImmutableList.Builder<>();
    for (ByteString content : blobs) {
      if (content.size() == 0) {
        digests.add(digestUtil.empty());
        continue;
      }

      Blob blob = new Blob(content, digestUtil);
      Digest blobDigest = blob.getDigest();
      contentAddressableStorage.put(blob);
      digests.add(blobDigest);
    }
    return digests.build();
  }

  @Override
  public String getBlobName(Digest blobDigest) {
    throw new UnsupportedOperationException();
  }

  private ByteString getBlobImpl(Digest blobDigest) {
    if (contentAddressableStorage.contains(blobDigest)) {
      Blob blob = contentAddressableStorage.get(blobDigest);
      if (blob == null) {
        return null;
      }
      return blob.getData();
    }
    return null;
  }

  @Override
  public ByteString getBlob(Digest blobDigest, long offset, long limit) throws IOException {
    ByteString content;
    synchronized (contentAddressableStorage.acquire(blobDigest)) {
      content = getBlobImpl(blobDigest);
      try {
        if (content == null) {
          backplane.removeBlobLocation(blobDigest, getName());
          return null;
        }
      } finally {
        contentAddressableStorage.release(blobDigest);
      }
    }
    if (offset != 0 || limit != 0) {
      content = content.substring((int) offset, (int) (limit == 0 ? (content.size() - offset) : (limit + offset)));
    }
    return content;
  }

  // write through fetch with local lookup
  public ByteString fetchBlob(Digest blobDigest) throws InterruptedException, IOException {
    return fetcher.fetchBlob(blobDigest);
  }

  @Override
  public Digest putBlob(ByteString content) throws IOException {
    if (content.size() == 0) {
      return digestUtil.empty();
    }

    Blob blob = new Blob(content, digestUtil);
    contentAddressableStorage.put(blob);

    return blob.getDigest();
  }

  protected TokenizableIterator<Directory> createTreeIterator(
      Digest rootDigest, String pageToken) throws InterruptedException, IOException {
    final GetDirectoryFunction getDirectoryFunction;
    Iterable<Directory> directories = backplane.getTree(rootDigest);
    if (directories != null) {
      getDirectoryFunction = createDirectoriesIndex(directories)::get;
    } else {
      getDirectoryFunction = (digest) -> expectDirectory(fetchBlob(digest));
    }
    return new TreeIterator(getDirectoryFunction, rootDigest, pageToken);
  }

  private Directory expectDirectory(ByteString directoryBlob) {
    try {
      if (directoryBlob != null) {
        return Directory.parseFrom(directoryBlob);
      }
    } catch(IOException e) {
      e.printStackTrace();
    }
    return null;
  }

  @Override
  public String getTree(
      Digest rootDigest,
      int pageSize,
      String pageToken,
      ImmutableList.Builder<Directory> directories,
      boolean acceptMissing) throws InterruptedException, IOException {
    if (pageSize == 0) {
      pageSize = 1024; // getTreeDefaultPageSize();
    }
    if (pageSize >= 0 && pageSize > 1024 /* getTreeMaxPageSize() */) {
      pageSize = 1024; // getTreeMaxPageSize();
    }

    TokenizableIterator<Directory> iter =
        createTreeIterator(rootDigest, pageToken);

    while (iter.hasNext() && pageSize != 0) {
      Directory directory = iter.next();
      // If part of the tree is missing from the CAS, the server will return the
      // portion present and omit the rest.
      if (directory != null) {
        directories.add(directory);
        if (pageSize > 0) {
          pageSize--;
        }
      } else if (!acceptMissing) {
        throw new IOException("directory not found");
      }
    }
    return iter.toNextPageToken();
  }

  @Override
  public OutputStream getStreamOutput(String name) {
    throw new UnsupportedOperationException();
  }

  @Override
  public InputStream newStreamInput(String name) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void execute(
      Action action,
      boolean skipCacheLookup,
      int totalInputFileCount,
      long totalInputFileBytes,
      Consumer<Operation> onOperation) {
    throw new UnsupportedOperationException();
  }

  private void matchResettable(Platform platform, Predicate<Operation> onMatch) throws InterruptedException, IOException {
    while (!Thread.interrupted()) {
      try {
        String operationName = null;
        do {
          operationName = backplane.dispatchOperation();
        } while (operationName == null);

        // FIXME platform match

        Operation operation = backplane.getOperation(operationName);
        boolean success = onMatch.test(operation);
      } catch (SocketTimeoutException e) {
        // ignore
      } catch (SocketException e) {
        if (!e.getMessage().equals("Connection reset")) {
          throw e;
        }
      }
    }
  }

  private void matchInterruptible(Platform platform, Predicate<Operation> onMatch) throws InterruptedException, IOException {
    matchResettable(platform, onMatch);
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
  }

  @Override
  public void match(Platform platform, Predicate<Operation> onMatch) throws InterruptedException {
    try {
      matchInterruptible(platform, onMatch);
    } catch (IOException e) {
      throw Status.fromThrowable(e).asRuntimeException();
    }
  }

  @Override
  public boolean putOperation(Operation operation) {
    try {
      return backplane.putOperation(operation, expectExecuteOperationMetadata(operation).getStage());
    } catch (IOException e) {
      throw Status.fromThrowable(e).asRuntimeException();
    }
  }

  @Override
  public boolean pollOperation(String operationName, Stage stage) {
    throw new UnsupportedOperationException();
  }

  // returns nextPageToken suitable for list restart
  @Override
  public String listOperations(
      int pageSize,
      String pageToken,
      String filter,
      ImmutableList.Builder<Operation> operations) {
    throw new UnsupportedOperationException();
  }

  @Override
  protected boolean matchOperation(Operation operation) { throw new UnsupportedOperationException(); }

  @Override
  protected void enqueueOperation(Operation operation) { throw new UnsupportedOperationException(); }

  @Override
  protected Object operationLock(String operationName) { throw new UnsupportedOperationException(); }

  @Override
  protected Operation createOperation(ActionKey actionKey) { throw new UnsupportedOperationException(); }

  @Override
  protected int getTreeDefaultPageSize() { return 1024; }

  @Override
  protected int getTreeMaxPageSize() { return 1024; }

  @Override
  public Operation getOperation(String name) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void cancelOperation(String name) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void deleteOperation(String name) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean watchOperation(
      String operationName,
      boolean watchInitialState,
      Predicate<Operation> watcher) {
    throw new UnsupportedOperationException();
  }

  @Override
  protected ExecuteOperationMetadata expectExecuteOperationMetadata(
      Operation operation) {
    if (operation.getMetadata().is(QueuedOperationMetadata.class)) {
      try {
        return operation.getMetadata().unpack(QueuedOperationMetadata.class).getExecuteOperationMetadata();
      } catch(InvalidProtocolBufferException e) {
        e.printStackTrace();
        return null;
      }
    } else if (operation.getMetadata().is(CompletedOperationMetadata.class)) {
      try {
        return operation.getMetadata().unpack(CompletedOperationMetadata.class).getExecuteOperationMetadata();
      } catch(InvalidProtocolBufferException e) {
        e.printStackTrace();
        return null;
      }
    } else {
      return super.expectExecuteOperationMetadata(operation);
    }
  }

  public void cacheOperationActionInputTree(String operationName) throws InterruptedException, IOException {
    Operation operation = getOperation(operationName);
    Action action = expectAction(operation);
    Digest inputRoot = action.getInputRootDigest();
    backplane.putTree(inputRoot, getTreeDirectories(inputRoot));
  }

  public Operation stripOperation(Operation operation) {
    return operation.toBuilder()
        .setMetadata(Any.pack(expectExecuteOperationMetadata(operation)))
        .build();
  }

  public Operation stripQueuedOperation(Operation operation) {
    if (operation.getMetadata().is(QueuedOperationMetadata.class)) {
      operation = operation.toBuilder()
        .setMetadata(Any.pack(expectExecuteOperationMetadata(operation)))
        .build();
    }
    return operation;
  }
}