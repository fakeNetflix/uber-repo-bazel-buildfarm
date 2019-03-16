// Copyright 2018 The Bazel Authors. All rights reserved.
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
import build.buildfarm.worker.InputStreamFactory;
import com.google.devtools.remoteexecution.v1test.Digest;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;

class FailoverInputStreamFactory implements InputStreamFactory {
  private final InputStreamFactory primary;
  private final InputStreamFactory delegate;

  FailoverInputStreamFactory(InputStreamFactory primary, InputStreamFactory delegate) {
    this.primary = primary;
    this.delegate = delegate;
  }

  @Override
  public InputStream newInput(Digest blobDigest, long offset) throws IOException, InterruptedException {
    try {
      return primary.newInput(blobDigest, offset);
    } catch (NoSuchFileException e) {
      return delegate.newInput(blobDigest, offset);
    }
  }
};
