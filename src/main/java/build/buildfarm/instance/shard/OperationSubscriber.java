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

package build.buildfarm.instance.shard;

import static java.lang.String.format;

import build.buildfarm.instance.WatchFuture;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.longrunning.Operation;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.Predicate;
import java.util.logging.Logger;
import redis.clients.jedis.Client;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

abstract class OperationSubscriber extends JedisPubSub {
  private static final Logger logger = Logger.getLogger(OperationSubscriber.class.getName());

  abstract static class TimedWatchFuture extends WatchFuture {
    private final TimedWatcher<Operation> watcher;

    TimedWatchFuture(TimedWatcher<Operation> watcher) {
      super(watcher::observe);
      this.watcher = watcher;
    }

    TimedWatcher<Operation> getWatcher() {
      return watcher;
    }
  }

  private final ListMultimap<String, TimedWatchFuture> watchers;
  private final ListeningExecutorService executorService;

  OperationSubscriber(
      ListMultimap<String, TimedWatchFuture> watchers,
      ExecutorService executorService) {
    this.watchers = watchers;
    this.executorService = MoreExecutors.listeningDecorator(executorService);
  }

  public List<String> watchedOperationChannels() {
    synchronized (watchers) {
      return ImmutableList.copyOf(watchers.keySet());
    }
  }

  public List<String> expiredWatchedOperationChannels(Instant now) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    synchronized (watchers) {
      for (String channel : watchers.keySet()) {
        for (TimedWatchFuture watchFuture : watchers.get(channel)) {
          if (watchFuture.getWatcher().isExpiredAt(now)) {
            builder.add(channel);
            break;
          }
        }
      }
    }
    return builder.build();
  }

  // synchronizing on these because the client has been observed to
  // cause protocol desynchronization for multiple concurrent calls
  @Override
  public synchronized void subscribe(String... channels) {
    super.subscribe(channels);
  }

  @Override
  public synchronized void unsubscribe() {
    super.unsubscribe();
  }

  @Override
  public synchronized void unsubscribe(String... channels) {
    super.unsubscribe(channels);
  }

  public ListenableFuture<Void> watch(String channel, TimedWatcher<Operation> watcher) {
    TimedWatchFuture watchFuture = new TimedWatchFuture(watcher) {
      @Override
      public void unwatch() {
        OperationSubscriber.this.unwatch(channel, this);
      }
    };
    boolean hasSubscribed;
    synchronized (watchers) {
      // use prefix
      hasSubscribed = watchers.containsKey(channel);
      watchers.put(channel, watchFuture);
      if (!hasSubscribed) {
        subscribe(channel);
      }
    }
    return watchFuture;
  }

  public void unwatch(String channel, TimedWatchFuture watchFuture) {
    synchronized (watchers) {
      if (watchers.remove(channel, watchFuture) &&
          !watchers.containsKey(channel)) {
        unsubscribe(channel);
      }
    }
  }

  public void resetWatchers(String channel, Instant expiresAt) {
    List<TimedWatchFuture> operationWatchers = watchers.get(channel);
    synchronized (watchers) {
      for (TimedWatchFuture watchFuture : operationWatchers) {
        watchFuture.getWatcher().reset(expiresAt);
      }
    }
  }

  private void terminateExpiredWatchers(String channel, Instant now) {
    onOperation(channel, null, (watcher) -> {
      boolean expired = watcher.isExpiredAt(now);
      if (expired) {
        logger.severe(format("Terminating expired watcher of %s because: %s >= %s", channel, now.toString(), watcher.getExpiresAt()));
      }
      return expired;
    }, now);
  }

  public void onOperation(String channel, Operation operation, Instant expiresAt) {
    onOperation(channel, operation, (watcher) -> true, expiresAt);
  }

  private static class StillWatchingWatcher {
    public final boolean stillWatching;
    public final TimedWatcher<Operation> watcher;

    StillWatchingWatcher(boolean stillWatching, TimedWatcher<Operation> watcher) {
      this.stillWatching = stillWatching;
      this.watcher = watcher;
    }
  }

  private void onOperation(
      String channel,
      Operation operation,
      Predicate<TimedWatcher<Operation>> shouldObserve,
      Instant expiresAt) {
    List<TimedWatchFuture> operationWatchers = watchers.get(channel);
    boolean observe = operation == null || operation.hasMetadata();
    synchronized (watchers) {
      ImmutableList.Builder<Consumer<Operation>> observers = ImmutableList.builder();
      for (TimedWatchFuture watchFuture : operationWatchers) {
        TimedWatcher<Operation> watcher = watchFuture.getWatcher();
        watcher.reset(expiresAt);
        if (shouldObserve.test(watcher)) {
          observers.add(watchFuture::observe);
        }
      }
      for (Consumer<Operation> observer : observers.build()) {
        executorService.execute(() -> {
          if (observe) {
            observer.accept(operation);
          }
        });
      }
    }
  }

  @Override
  public void onMessage(String channel, String message) {
    if (message != null && message.equals("expire")) {
      terminateExpiredWatchers(channel, Instant.now());
    } else {
      Operation operation = message == null
          ? null : RedisShardBackplane.parseOperationJson(message);
      onOperation(channel, operation, nextExpiresAt());
    }
  }

  @Override
  public void onSubscribe(String channel, int subscribedChannels) {
  }

  private String[] placeholderChannel() {
    String[] channels = new String[1];
    channels[0] = "placeholder-shard-subscription";
    return channels;
  }

  @Override
  public void proceed(Client client, String... channels) {
    if (channels.length == 0) {
      channels = placeholderChannel();
    }
    super.proceed(client, channels);
  }

  protected abstract Instant nextExpiresAt();
}
