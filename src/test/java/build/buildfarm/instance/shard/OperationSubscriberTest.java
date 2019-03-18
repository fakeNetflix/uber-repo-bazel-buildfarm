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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static redis.clients.jedis.Protocol.Keyword.SUBSCRIBE;
import static redis.clients.jedis.Protocol.Keyword.UNSUBSCRIBE;

import build.buildfarm.instance.shard.OperationSubscriber.TimedWatchFuture;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Sets;
import com.google.longrunning.Operation;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import redis.clients.jedis.Client;

@RunWith(JUnit4.class)
public class OperationSubscriberTest {
  /* he cannot unsee */
  private static class LidlessTimedWatchFuture extends TimedWatchFuture {
    LidlessTimedWatchFuture(TimedWatcher timedWatcher) {
      super(timedWatcher);
    }

    @Override
    public void unwatch() {
      throw new UnsupportedOperationException();
    }
  }

  private static class TestClient extends Client {
    private final Set<String> subscriptions = Sets.newConcurrentHashSet();
    private final BlockingQueue<List<Object>> replyQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<List<Object>> pendingReplies = new LinkedBlockingQueue<>();

    Set<String> getSubscriptions() {
      return subscriptions;
    }

    @Override
    public List<Object> getRawObjectMultiBulkReply() {
      try {
        return replyQueue.take();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }

    @Override
    public void subscribe(String... channels) {
      for (String channel : channels) {
        if (subscriptions.add(channel)) {
          pendingReplies.add(ImmutableList.of(SUBSCRIBE.raw, channel.getBytes(), (long) subscriptions.size()));
        } else {
          throw new IllegalStateException("subscribe to already subscribed channel: " + channel);
        }
      }
    }

    @Override
    public void unsubscribe() {
      long counter = subscriptions.size();
      for (String channel : subscriptions) {
        pendingReplies.add(ImmutableList.of(UNSUBSCRIBE.raw, channel.getBytes(), --counter));
      }
      subscriptions.clear();
    }

    @Override
    public void unsubscribe(String... channels) {
      for (String channel : channels) {
        if (subscriptions.remove(channel)) {
          pendingReplies.add(ImmutableList.of(UNSUBSCRIBE.raw, channel.getBytes(), (long) subscriptions.size()));
        } else {
          throw new IllegalStateException("unsubscribe from unknown channel: " + channel);
        }
      }
    }

    @Override
    public void flush() {
      pendingReplies.drainTo(replyQueue);
    }
  };

  class UnexpiringOperationSubscriber extends OperationSubscriber {
    UnexpiringOperationSubscriber(
        ListMultimap<String, TimedWatchFuture> watchers,
        Executor executor) {
      super(watchers, executor);
    }

    @Override
    protected Instant nextExpiresAt() {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  public void novelChannelWatcherSubscribes() throws InterruptedException {
    ListMultimap<String, TimedWatchFuture> watchers =
        Multimaps.<String, TimedWatchFuture>synchronizedListMultimap(
            MultimapBuilder.linkedHashKeys().arrayListValues().build());
    OperationSubscriber operationSubscriber = new UnexpiringOperationSubscriber(watchers, directExecutor());

    TestClient testClient = new TestClient();
    Thread proceedThread = new Thread(() -> operationSubscriber.proceed(testClient));
    proceedThread.start();
    while (!operationSubscriber.isSubscribed()) {
      MICROSECONDS.sleep(10);
    }

    String novelChannel = "novel-channel";
    TimedWatcher novelWatcher = new UnobservableWatcher();
    operationSubscriber.watch(novelChannel, novelWatcher);
    assertThat(Iterables.getOnlyElement(watchers.get(novelChannel)).getWatcher()).isEqualTo(novelWatcher);
    String[] channels = new String[1];
    channels[0] = novelChannel;
    assertThat(testClient.getSubscriptions()).contains(novelChannel);
    operationSubscriber.unsubscribe();
    proceedThread.join();
  }

  @Test
  public void watchedOperationChannelsReflectsWatchers() {
    ListMultimap<String, TimedWatchFuture> watchers =
        MultimapBuilder.linkedHashKeys().arrayListValues().build();
    OperationSubscriber operationSubscriber = new UnexpiringOperationSubscriber(watchers, /* executor=*/ null);
    assertThat(operationSubscriber.watchedOperationChannels()).isEmpty();
    String addedChannel = "added-channel";
    watchers.put(addedChannel, null);
    assertThat(operationSubscriber.watchedOperationChannels()).containsExactly(addedChannel);
    watchers.removeAll(addedChannel);
    assertThat(operationSubscriber.watchedOperationChannels()).isEmpty();
  }

  @Test
  public void expiredWatchedOperationChannelsReflectsWatchers() {
    ListMultimap<String, TimedWatchFuture> watchers =
        MultimapBuilder.linkedHashKeys().arrayListValues().build();
    OperationSubscriber operationSubscriber = new UnexpiringOperationSubscriber(watchers, /* executor=*/ null);

    TimedWatcher unexpiredWatcher = new UnobservableWatcher(Instant.MAX);
    TimedWatcher expiredWatcher = new UnobservableWatcher(Instant.EPOCH);
    Instant now = Instant.now();
    // EPOCH < now < MAX

    assertThat(operationSubscriber.expiredWatchedOperationChannels(now)).isEmpty();

    String unexpiredChannel = "channel-with-unexpired-watcher";
    watchers.put(unexpiredChannel, new LidlessTimedWatchFuture(unexpiredWatcher));
    assertThat(operationSubscriber.expiredWatchedOperationChannels(now)).isEmpty();

    String expiredChannel = "channel-with-expired-watcher";
    watchers.put(expiredChannel, new LidlessTimedWatchFuture(expiredWatcher));
    assertThat(operationSubscriber.expiredWatchedOperationChannels(now)).containsExactly(expiredChannel);

    String mixedChannel = "channel-with-some-expired-watchers";
    watchers.put(mixedChannel, new LidlessTimedWatchFuture(unexpiredWatcher));
    watchers.put(mixedChannel, new LidlessTimedWatchFuture(expiredWatcher));
    watchers.put(mixedChannel, new LidlessTimedWatchFuture(expiredWatcher));
    watchers.put(mixedChannel, new LidlessTimedWatchFuture(unexpiredWatcher));
    assertThat(operationSubscriber.expiredWatchedOperationChannels(now)).containsExactly(expiredChannel, mixedChannel);

    watchers.removeAll(expiredChannel);
    assertThat(operationSubscriber.expiredWatchedOperationChannels(now)).containsExactly(mixedChannel);

    watchers.removeAll(mixedChannel);
    assertThat(operationSubscriber.expiredWatchedOperationChannels(now)).isEmpty();
  }

  @Test
  public void existingChannelWatcherSuppressesSubscription() {
    ListMultimap<String, TimedWatchFuture> watchers =
        MultimapBuilder.linkedHashKeys().arrayListValues().build();
    OperationSubscriber operationSubscriber = new UnexpiringOperationSubscriber(watchers, directExecutor());
    String existingChannel = "existing-channel";
    TimedWatcher existingWatcher = new UnobservableWatcher();
    watchers.put(existingChannel, new LidlessTimedWatchFuture(existingWatcher));
    TimedWatcher novelWatcher = new UnobservableWatcher();
    operationSubscriber.watch(existingChannel, novelWatcher);
    assertThat(watchers.get(existingChannel).size()).isEqualTo(2);
  }

  @Test
  public void nullMessageUnsubscribesWatcher() throws InterruptedException {
    ListMultimap<String, TimedWatchFuture> watchers =
        Multimaps.<String, TimedWatchFuture>synchronizedListMultimap(
            MultimapBuilder.linkedHashKeys().arrayListValues().build());
    OperationSubscriber operationSubscriber = new OperationSubscriber(watchers, directExecutor()) {
      @Override
      protected Instant nextExpiresAt() {
        return Instant.now();
      }
    };

    TestClient testClient = new TestClient();
    Thread proceedThread = new Thread(() -> operationSubscriber.proceed(testClient));
    proceedThread.start();
    while (!operationSubscriber.isSubscribed()) {
      MICROSECONDS.sleep(10);
    }

    String nullMessageChannel = "null-message-channel";
    TimedWatcher nullMessageWatcher = new TimedWatcher(Instant.now()) {
      @Override
      public void observe(Operation operation) {
        if (operation != null) {
          throw new UnsupportedOperationException();
        }
      }
    };
    operationSubscriber.watch(nullMessageChannel, nullMessageWatcher);
    operationSubscriber.onMessage(nullMessageChannel, /* message=*/ null);
    assertThat(testClient.getSubscriptions()).doesNotContain(nullMessageChannel);
    operationSubscriber.unsubscribe();
    proceedThread.join();
  }

  @Test
  public void shouldResetWatchers() {
    ListMultimap<String, TimedWatchFuture> watchers =
        MultimapBuilder.linkedHashKeys().arrayListValues().build();
    TimedWatcher resetWatcher = new UnobservableWatcher(Instant.EPOCH);

    Instant now = Instant.now();
    assertThat(resetWatcher.isExpiredAt(now)).isTrue();

    String resetChannel = "reset-channel";
    watchers.put(resetChannel, new LidlessTimedWatchFuture(resetWatcher));

    OperationSubscriber operationSubscriber = new UnexpiringOperationSubscriber(watchers, /* executor=*/ null);
    operationSubscriber.resetWatchers(resetChannel, Instant.MAX);
    assertThat(resetWatcher.isExpiredAt(now)).isFalse();
  }

  @Test
  public void terminatesExpiredWatchersOnExpireMessage() {
    ListMultimap<String, TimedWatchFuture> watchers =
        MultimapBuilder.linkedHashKeys().arrayListValues().build();
    TimedWatcher expiredWatcher = mock(TimedWatcher.class);
    when(expiredWatcher.isExpiredAt(any(Instant.class))).thenReturn(true);

    OperationSubscriber operationSubscriber = new UnexpiringOperationSubscriber(watchers, directExecutor());

    String expireChannel = "expire-channel";
    TimedWatchFuture watchFuture = new TimedWatchFuture(expiredWatcher) {
      @Override
      public void unwatch() {
        operationSubscriber.unwatch(expireChannel, this);
      }
    };
    watchers.put(expireChannel, watchFuture);

    operationSubscriber.onMessage(expireChannel, "expire");
    verify(expiredWatcher, times(1)).observe(null);
    assertThat(watchers.get(expireChannel)).isEmpty();
  }
}
