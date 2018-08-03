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

package build.buildfarm.instance.memory;

import build.buildfarm.instance.Instance.CommittingOutputStream;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
import java.io.InputStream;

class ByteStringStreamSource {
  private final CommittingOutputStream outputStream;

  private final Object bufferSync;
  private ByteString buffer;
  private boolean closed;

  public ByteStringStreamSource() {
    buffer = ByteString.EMPTY;
    outputStream = new CommittingOutputStream() {
      long committedSize = 0;
      SettableFuture<Long> committedFuture = SettableFuture.create();

      @Override
      public void write(int b) {
        byte[] buf = new byte[1];
        buf[0] = (byte) b;
        write(buf);
      }

      @Override
      public void write(byte[] b) {
        write(b, 0, b.length);
      }

      @Override
      public void write(byte[] b, int off, int len) {
        synchronized (bufferSync) {
          buffer = buffer.concat(ByteString.copyFrom(b, off, len));
          bufferSync.notifyAll();
        }
        committedSize += len;
      }

      @Override
      public void close() {
        synchronized (bufferSync) {
          closed = true;
          bufferSync.notifyAll();
        }
        committedFuture.set(committedSize);
      }

      @Override
      public ListenableFuture<Long> getCommittedFuture() {
        return committedFuture;
      }
    };
    bufferSync = new Object();
    closed = false;
  }

  public boolean isClosed() {
    return closed;
  }

  public CommittingOutputStream getOutputStream() {
    return outputStream;
  }

  public InputStream openStream() {
    return new InputStream() {
      private int offset = 0;

      @Override
      public int available() {
        synchronized (bufferSync) {
          return availableUnsynchronized();
        }
      }

      private int availableUnsynchronized() {
        return buffer.size() - offset;
      }

      @Override
      public long skip(long n) {
        if (n <= 0) {
          return 0;
        }
        synchronized (bufferSync) {
          try {
            while (!closed && availableUnsynchronized() == 0) {
              bufferSync.wait();
            }
          } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          n = Math.min(availableUnsynchronized(), n);
        }
        offset += n;
        return n;
      }

      @Override
      public int read() {
        byte[] buf = new byte[1];
        if (read(buf) < 0)
          return -1;
        return buf[0];
      }

      @Override
      public int read(byte[] b) {
        return read(b, 0, b.length);
      }

      @Override
      public int read(byte[] b, int off, int len) {
        try {
          if (len == 0) {
            return 0;
          }
          synchronized (bufferSync) {
            while (availableUnsynchronized() == 0) {
              if (closed) {
                return -1;
              }
              bufferSync.wait();
            }
            len = Math.min(available(), len);
            buffer.copyTo(b, offset, off, len);
          }
          offset += len;
          return len;
        } catch(InterruptedException e) {
          Thread.currentThread().interrupt();
          return -1;
        }
      }
    };
  }
}
