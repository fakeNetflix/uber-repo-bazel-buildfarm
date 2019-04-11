package build.buildfarm.common.grpc;

import static com.google.common.base.Preconditions.checkNotNull;

import build.buildfarm.common.Write;
import com.google.bytestream.ByteStreamGrpc.ByteStreamBlockingStub;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.bytestream.ByteStreamProto.WriteRequest;
import com.google.bytestream.ByteStreamProto.WriteResponse;
import com.google.bytestream.ByteStreamProto.QueryWriteStatusRequest;
import com.google.bytestream.ByteStreamProto.QueryWriteStatusResponse;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public class StubWriteOutputStream extends OutputStream implements Write {
  public static final long UNLIMITED_EXPECTED_SIZE = Long.MAX_VALUE;

  private static final int CHUNK_SIZE = 16 * 1024;

  private final Supplier<ByteStreamBlockingStub> bsBlockingStub;
  private final Supplier<ByteStreamStub> bsStub;
  private final String resourceName;
  private final SettableFuture<Void> writeFuture = SettableFuture.create();
  private final long expectedSize;
  private final boolean autoflush;
  private final byte buf[];
  private boolean wasReset = false;
  private final Supplier<QueryWriteStatusResponse> writeStatus = Suppliers.memoize(
      new Supplier() {
        @Override
        public QueryWriteStatusResponse get() {
          if (wasReset) {
            return QueryWriteStatusResponse.newBuilder()
                .setCommittedSize(0)
                .setComplete(false)
                .build();
          }
          QueryWriteStatusResponse response = bsBlockingStub.get()
              .queryWriteStatus(QueryWriteStatusRequest.newBuilder()
                  .setResourceName(resourceName)
                  .build());
          if (response.getComplete()) {
            writeFuture.set(null);
          }
          return response;
        }
      });
  private boolean sentResourceName = false;
  private int offset = 0;
  private long writtenBytes = 0;
  private StreamObserver<WriteRequest> writeObserver = null;
  private long deadlineAfter = 0;
  private TimeUnit deadlineAfterUnits = null;

  static class WriteCompleteException extends IOException {
  }

  public StubWriteOutputStream(
      Supplier<ByteStreamBlockingStub> bsBlockingStub,
      Supplier<ByteStreamStub> bsStub,
      String resourceName,
      long expectedSize,
      boolean autoflush) {
    this.bsBlockingStub = bsBlockingStub;
    this.bsStub = bsStub;
    this.resourceName = resourceName;
    this.expectedSize = expectedSize;
    this.autoflush = autoflush;
    buf = new byte[(int) Math.min(CHUNK_SIZE, expectedSize)];
  }

  @Override
  public void close() throws IOException {
    if (!checkComplete()) {
      boolean finishWrite = expectedSize == UNLIMITED_EXPECTED_SIZE;
      if (finishWrite || offset != 0) {
        initiateWrite();
        flushSome(finishWrite);
      }
      if (writeObserver != null) {
        if (finishWrite || getCommittedSize() + offset == expectedSize) {
          writeObserver.onCompleted();
        } else {
          writeObserver.onError(Status.CANCELLED.asException());
        }
        writeObserver = null;
      }
    }
  }

  private void flushSome(boolean finishWrite) {
    WriteRequest.Builder request = WriteRequest.newBuilder()
        .setWriteOffset(getCommittedSize())
        .setData(ByteString.copyFrom(buf, 0, offset))
        .setFinishWrite(finishWrite);
    if (!sentResourceName) {
      request.setResourceName(resourceName);
    }
    writeObserver.onNext(request.build());
    writtenBytes += offset;
    offset = 0;
    sentResourceName = true;
  }

  @Override
  public void flush() throws IOException {
    if (!checkComplete()) {
      if (offset != 0) {
        initiateWrite();
        flushSome(getCommittedSize() + offset == expectedSize);
      }
    }
  }

  private boolean checkComplete() throws IOException {
    try {
      return writeFuture.isDone() && writeFuture.get() == null;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException) {
        throw (IOException) cause;
      }
      throw new IOException(cause);
    } catch (InterruptedException e) {
      // unlikely, since we only get for isDone()
      throw new IOException(e);
    }
  }

  private void initiateWrite() throws IOException {
    if (writeObserver == null) {
      checkNotNull(deadlineAfterUnits);
      writeObserver = bsStub.get()
          .withDeadlineAfter(deadlineAfter, deadlineAfterUnits)
          .write(
              new StreamObserver<WriteResponse>() {
                @Override
                public void onNext(WriteResponse response) {
                  writtenBytes += response.getCommittedSize() - getCommittedSize();
                  writeFuture.set(null);
                }

                @Override
                public void onError(Throwable t) {
                  writeFuture.setException(t);
                }

                @Override
                public void onCompleted() {
                  writeObserver = null;
                }
              });
    }
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    if (isComplete() || checkComplete()) {
      throw new WriteCompleteException();
    }
    if (getCommittedSize() + offset + len > expectedSize) {
      throw new IndexOutOfBoundsException("write would exceed expected size");
    }
    boolean lastFlushed = false;
    while (len > 0 && !checkComplete()) {
      lastFlushed = false;
      int copyLen = Math.min(buf.length - offset, len);
      System.arraycopy(b, off, buf, offset, copyLen);
      offset += copyLen;
      off += copyLen;
      len -= copyLen;
      if (offset == buf.length || getCommittedSize() + offset == expectedSize) {
        flush();
        lastFlushed = true;
      }
    }
    if (!lastFlushed && autoflush) {
      flush();
    }
  }

  @Override
  public void write(int b) throws IOException {
    if (isComplete() || checkComplete()) {
      throw new WriteCompleteException();
    }
    if (!checkComplete()) {
      buf[offset++] = (byte) b;
      if (autoflush || offset == buf.length) {
        flush();
      }
    }
  }

  // Write methods

  @Override
  public long getCommittedSize() {
    return writeStatus.get().getCommittedSize() + writtenBytes;
  }

  @Override
  public boolean isComplete() {
    return writeStatus.get().getComplete() || getCommittedSize() == expectedSize;
  }

  @Override
  public OutputStream getOutput(long deadlineAfter, TimeUnit deadlineAfterUnits) {
    this.deadlineAfter = deadlineAfter;
    this.deadlineAfterUnits = deadlineAfterUnits;
    return this;
  }

  @Override
  public void reset() {
    wasReset = true;
    offset = 0;
    writtenBytes = 0;
  }

  @Override
  public void addListener(Runnable onCompleted, Executor executor) {
    writeFuture.addListener(onCompleted, executor);
  }
}
