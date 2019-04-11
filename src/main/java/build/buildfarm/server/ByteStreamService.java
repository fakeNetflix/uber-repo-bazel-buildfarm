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

import static build.buildfarm.common.UrlPath.detectResourceOperation;
import static build.buildfarm.common.UrlPath.parseBlobDigest;
import static build.buildfarm.common.UrlPath.parseUploadBlobDigest;
import static build.buildfarm.common.UrlPath.parseUploadBlobUUID;
import static io.grpc.Status.INVALID_ARGUMENT;
import static io.grpc.Status.NOT_FOUND;
import static io.grpc.Status.OUT_OF_RANGE;
import static java.lang.String.format;
import static java.util.logging.Level.SEVERE;

import build.bazel.remote.execution.v2.Digest;
import build.buildfarm.common.UrlPath.InvalidResourceNameException;
import build.buildfarm.common.Write;
import build.buildfarm.instance.Instance;
import com.google.bytestream.ByteStreamGrpc.ByteStreamImplBase;
import com.google.bytestream.ByteStreamProto.QueryWriteStatusRequest;
import com.google.bytestream.ByteStreamProto.QueryWriteStatusResponse;
import com.google.bytestream.ByteStreamProto.ReadRequest;
import com.google.bytestream.ByteStreamProto.ReadResponse;
import com.google.bytestream.ByteStreamProto.WriteRequest;
import com.google.bytestream.ByteStreamProto.WriteResponse;
import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Context.CancellableContext;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.NoSuchFileException;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class ByteStreamService extends ByteStreamImplBase {
  private static final Logger logger = Logger.getLogger(ByteStreamService.class.getName());

  private static int CHUNK_SIZE = 64 * 1024;

  private final long deadlineAfter;
  private final TimeUnit deadlineAfterUnits;
  private final Instances instances;

  public ByteStreamService(
      Instances instances,
      long deadlineAfter,
      TimeUnit deadlineAfterUnits) {
    this.instances = instances;
    this.deadlineAfter = deadlineAfter;
    this.deadlineAfterUnits = deadlineAfterUnits;
  }

  void readFrom(
      InputStream in,
      long limit,
      StreamObserver<ReadResponse> responseObserver)
      throws IOException {
    byte buf[] = new byte[CHUNK_SIZE];
    boolean unlimited = limit == 0;
    long remaining = limit;
    boolean complete = false;
    while (!complete) {
      int readBytes = in.read(buf, 0, (int) Math.min(remaining, buf.length));
      if (readBytes == -1) {
        if (!unlimited) {
          responseObserver.onError(OUT_OF_RANGE.asException());
          remaining = -1;
        }
        complete = true;
      } else if (readBytes > 0) {
        if (readBytes > remaining) {
          logger.warning(format("read %d bytes, expected %d", readBytes, remaining));
          readBytes = (int) remaining;
        }
        responseObserver.onNext(ReadResponse.newBuilder()
            .setData(ByteString.copyFrom(buf, 0, readBytes))
            .build());
        remaining -= readBytes;
        complete = remaining == 0;
      }
    }
    if (remaining == 0) {
      responseObserver.onCompleted();
    }
  }

  void readLimitedBlob(
      Instance instance,
      Digest digest,
      long offset,
      long limit,
      StreamObserver<ReadResponse> responseObserver) {
    try (InputStream in = instance.newBlobInput(digest, offset, deadlineAfter, deadlineAfterUnits)) {
      readFrom(in, limit, responseObserver);
    } catch (NoSuchFileException e) {
      responseObserver.onError(NOT_FOUND.asException());
    } catch (IOException e) {
      responseObserver.onError(Status.fromThrowable(e).asException());
    }
  }

  void readBlob(
      Instance instance,
      Digest digest,
      long offset,
      long limit,
      StreamObserver<ReadResponse> responseObserver) {
    if (offset == digest.getSizeBytes()) {
      responseObserver.onCompleted();
    } else if (offset > digest.getSizeBytes()) {
      responseObserver.onError(OUT_OF_RANGE.asException());
    } else {
      long available = digest.getSizeBytes() - offset;
      if (limit == 0) {
        limit = available;
      } else {
        limit = Math.min(available, limit);
      }
      readLimitedBlob(instance, digest, offset, limit, responseObserver);
    }
  }

  void readOperationStream(
      Instance instance,
      String resourceName,
      long offset,
      long limit,
      StreamObserver<ReadResponse> responseObserver) {
    try (InputStream in = instance.newOperationStreamInput(resourceName, offset, deadlineAfter, deadlineAfterUnits)) {
      readFrom(in, limit, responseObserver);
    } catch (NoSuchFileException e) {
      responseObserver.onError(NOT_FOUND.asException());
    } catch (IOException e) {
      responseObserver.onError(Status.fromThrowable(e).asException());
    }
  }

  void maybeInstanceRead(
      String resourceName,
      long offset,
      long limit,
      StreamObserver<ReadResponse> responseObserver)
      throws InstanceNotFoundException, InvalidResourceNameException {
    switch (detectResourceOperation(resourceName)) {
      case Blob:
        readBlob(
            instances.getFromBlob(resourceName),
            parseBlobDigest(resourceName),
            offset,
            limit,
            responseObserver);
        break;
      case OperationStream:
        readOperationStream(
            instances.getFromOperationStream(resourceName),
            resourceName,
            offset,
            limit,
            responseObserver);
        break;
      case UploadBlob:
      default:
        responseObserver.onError(INVALID_ARGUMENT.asException());
        break;
    }
  }

  @Override
  public void read(
      ReadRequest request,
      StreamObserver<ReadResponse> responseObserver) {
    String resourceName = request.getResourceName();
    long offset = request.getReadOffset(), limit = request.getReadLimit();
    logger.finest(
        format(
            "read resource_name=%s offset=%d limit=%d",
            resourceName,
            offset,
            limit));

    try {
      maybeInstanceRead(resourceName, offset, limit, responseObserver);
    } catch (InstanceNotFoundException e) {
      responseObserver.onError(BuildFarmInstances.toStatusException(e));
    } catch (InvalidResourceNameException e) {
      responseObserver.onError(INVALID_ARGUMENT.withDescription(e.getMessage()).asException());
    }
  }

  @Override
  public void queryWriteStatus(
      QueryWriteStatusRequest request,
      StreamObserver<QueryWriteStatusResponse> responseObserver) {
    String resourceName = request.getResourceName();
    try {
      logger.fine(
          format(
              "queryWriteStatus(%s)",
              resourceName));
      Write write = getWrite(resourceName);
      responseObserver.onNext(
          QueryWriteStatusResponse.newBuilder()
              .setCommittedSize(write.getCommittedSize())
              .setComplete(write.isComplete())
              .build());
      responseObserver.onCompleted();
      logger.finer(
          format(
              "queryWriteStatus(%s) => committed_size = %d, complete = %s",
              resourceName,
              write.getCommittedSize(),
              write.isComplete()));
    } catch (InstanceNotFoundException e) {
      logger.log(SEVERE, format("queryWriteStatus(%s)", resourceName), e);
      responseObserver.onError(BuildFarmInstances.toStatusException(e));
    } catch (IllegalArgumentException|InvalidResourceNameException e) {
      logger.log(SEVERE, format("queryWriteStatus(%s)", resourceName), e);
      responseObserver.onError(Status.fromThrowable(e).asException());
    }
  }

  static Write getBlobWrite(
      Instance instance,
      Digest digest) {
    return new Write() {
      @Override
      public long getCommittedSize() {
        return isComplete() ? digest.getSizeBytes() : 0;
      }

      @Override
      public boolean isComplete() {
        return instance.containsBlob(digest);
      }

      @Override
      public OutputStream getOutput(long deadlineAfter, TimeUnit deadlineAfterUnits) throws IOException {
        throw new IOException("cannot get output of blob write");
      }

      @Override
      public void reset() {
        throw new RuntimeException("cannot reset a blob write");
      }

      @Override
      public void addListener(Runnable onCompleted, Executor executor) {
        throw new RuntimeException("cannot add listener to blob write");
      }
    };
  }

  static Write getUploadBlobWrite(
      Instance instance,
      Digest digest,
      UUID uuid) {
    return instance.getBlobWrite(digest, uuid);
  }

  static Write getOperationStreamWrite(
      Instance instance,
      String resourceName) {
    return instance.getOperationStreamWrite(resourceName);
  }

  Write getWrite(String resourceName) throws InstanceNotFoundException, InvalidResourceNameException {
    switch (detectResourceOperation(resourceName)) {
      case Blob:
        return getBlobWrite(
            instances.getFromBlob(resourceName),
            parseBlobDigest(resourceName));
      case UploadBlob:
        return getUploadBlobWrite(
            instances.getFromUploadBlob(resourceName),
            parseUploadBlobDigest(resourceName),
            parseUploadBlobUUID(resourceName));
      case OperationStream:
        return getOperationStreamWrite(
            instances.getFromOperationStream(resourceName),
            resourceName);
      default:
        throw new IllegalArgumentException();
    }
  }

  @Override
  public StreamObserver<WriteRequest> write(
      StreamObserver<WriteResponse> responseObserver) {
    return new WriteStreamObserver(
        instances,
        deadlineAfter,
        deadlineAfterUnits,
        responseObserver);
  }
}
