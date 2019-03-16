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

package build.buildfarm.instance.shard;

import static com.google.common.util.concurrent.Futures.addCallback;
import static com.google.common.util.concurrent.Futures.allAsList;
import static com.google.common.util.concurrent.Futures.transform;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.base.Predicates.notNull;

import build.buildfarm.common.ShardBackplane;
import build.buildfarm.instance.Instance;
import build.buildfarm.instance.stub.Retrier;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.remoteexecution.v1test.Digest;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.Status.Code;
import java.io.IOException;
import java.util.Set;
import java.util.function.Function;
import java.util.concurrent.ExecutorService;

public class Util {
  public static final Predicate<Status> SHARD_IS_RETRIABLE =
      st -> st.getCode() != Code.CANCELLED && Retrier.DEFAULT_IS_RETRIABLE.test(st);

  public static ListenableFuture<Set<String>> correctMissingBlob(
      ShardBackplane backplane,
      Set<String> workerSet,
      Set<String> originalLocationSet,
      Function<String, Instance> workerInstanceFactory,
      Digest digest,
      ExecutorService service) throws IOException {
    ListenableFuture<Set<String>> foundFuture = transform(
        allAsList(
            Iterables.transform(
                workerSet,
                new com.google.common.base.Function<String, ListenableFuture<String>>() {
                  @Override
                  public ListenableFuture<String> apply(String worker) {
                    return transform(
                        checkMissingBlobOnInstance(digest, workerInstanceFactory.apply(worker), service),
                        (missing) -> missing ? worker : null,
                        directExecutor());
                  }
                })),
        (workerResults) -> ImmutableSet.copyOf(Iterables.filter(workerResults, notNull())),
        service);
    addCallback(
        foundFuture,
        new FutureCallback<Set<String>>() {
          @Override
          public void onSuccess(Set<String> found) {
            try {
              backplane.adjustBlobLocations(
                  digest,
                  found,
                  Sets.difference(Sets.intersection(originalLocationSet, workerSet), found));
            } catch (IOException e) {
              e.printStackTrace();
            }
          }

          @Override
          public void onFailure(Throwable t) {
            // ignore
          }
        },
        service);
    return foundFuture;
  }

  private static ListenableFuture<Boolean> checkMissingBlobOnInstance(Digest digest, Instance instance, ExecutorService service) {
    return FluentFuture.from(instance.findMissingBlobs(ImmutableList.of(digest), service))
        .transform(Iterables::isEmpty, directExecutor())
        .catchingAsync(
            Throwable.class,
            (e) -> {
              Status status = Status.fromThrowable(e);
              if (status.getCode() == Code.UNAVAILABLE) {
              } else if (status.getCode() == Code.CANCELLED || Context.current().isCancelled()) {
                // do nothing further if we're cancelled
                throw status.asRuntimeException();
              } else if (SHARD_IS_RETRIABLE.test(status)) {
                return checkMissingBlobOnInstance(digest, instance, service);
              }
              throw Status.INTERNAL.withCause(e).asRuntimeException();
            }, service);
  }

}
