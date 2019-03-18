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

package build.buildfarm.common;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.ExecuteOperationMetadata.Stage;
import build.buildfarm.common.DigestUtil.ActionKey;
import build.buildfarm.common.ThreadSafety.ThreadSafe;
import build.buildfarm.common.Watcher;
import build.buildfarm.common.function.InterruptingRunnable;
import build.buildfarm.v1test.DispatchedOperation;
import build.buildfarm.v1test.ExecuteEntry;
import build.buildfarm.v1test.QueueEntry;
import build.buildfarm.v1test.ShardWorker;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.longrunning.Operation;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

public interface ShardBackplane {

  public final static class ActionCacheScanResult {
    public final String token;
    public final Iterable<Map.Entry<ActionKey, ActionResult>> entries;

    public ActionCacheScanResult(String token, Iterable<Map.Entry<ActionKey, ActionResult>> entries) {
      this.token = token;
      this.entries = entries;
    }
  }

  /**
   * Register a runnable for when the backplane cannot guarantee watch deliveries.
   * This runnable may throw InterruptedException
   *
   * onSubscribe is called via the subscription thread, and is not called
   * when operations are not listened to
   */
  InterruptingRunnable setOnUnsubscribe(InterruptingRunnable onUnsubscribe);

  /**
   * Start the backplane's operation
   */
  @ThreadSafe
  void start();

  /**
   * Stop the backplane's operation
   */
  @ThreadSafe
  void stop() throws InterruptedException;

  /**
   * Indicates whether the backplane has been stopped
   */
  @ThreadSafe
  boolean isStopped();

  /**
   * Adds a worker to the set of active workers.
   *
   * Returns true if the worker was newly added, and false if
   * it was already a member of the set.
   */
  @ThreadSafe
  boolean addWorker(ShardWorker shardWorker) throws IOException;

  /**
   * Removes a worker's name from the set of active workers.
   *
   * Return true if the worker was removed, and false if it
   * was not a member of the set.
   */
  @ThreadSafe
  boolean removeWorker(String workerName) throws IOException;

  /**
   * Returns a set of the names of all active workers.
   */
  @ThreadSafe
  Set<String> getWorkers() throws IOException;

  /**
   * The AC stores full ActionResult objects in a hash map where the key is the
   * digest of the action result and the value is the actual ActionResult
   * object.
   *
   * Retrieves and returns an action result from the hash map.
   */
  @ThreadSafe
  ActionResult getActionResult(ActionKey actionKey) throws IOException;

  /**
   * The AC stores full ActionResult objects in a hash map where the key is the
   * digest of the action result and the value is the actual ActionResult
   * object.
   *
   * Remove an action result from the hash map.
   */
  @ThreadSafe
  void removeActionResult(ActionKey actionKey) throws IOException;

  /**
   * Bulk remove action results
   */
  @ThreadSafe
  void removeActionResults(Iterable<ActionKey> actionKeys) throws IOException;

  /**
   * The AC stores full ActionResult objects in a hash map where the key is the
   * digest of the action result and the value is the actual ActionResult
   * object.
   *
   * Stores an action result in the hash map.
   */
  @ThreadSafe
  void putActionResult(ActionKey actionKey, ActionResult actionResult) throws IOException;

  /**
   * The CAS is represented as a map where the key is the digest of the blob
   * that is being stored and the value is a set of the names of the workers
   * where that blob is stored.
   *
   * Adds the name of a worker to the set of workers that store a blob.
   */
  @ThreadSafe
  void addBlobLocation(Digest blobDigest, String workerName) throws IOException;

  /**
   * Remove or add workers to a blob's location set as requested
   */
  @ThreadSafe
  public void adjustBlobLocations(Digest blobDigest, Set<String> addWorkers, Set<String> removeWorkers) throws IOException;

  /**
   * The CAS is represented as a map where the key is the digest of the blob
   * that is being stored and the value is a set of the names of the workers
   * where that blob is stored.
   *
   * Adds the name of a worker to the set of workers that store multiple blobs.
   */
  @ThreadSafe
  void addBlobsLocation(Iterable<Digest> blobDigest, String workerName) throws IOException;

  /**
   * The CAS is represented as a map where the key is the digest of the blob
   * that is being stored and the value is a set of the names of the workers
   * where that blob is stored.
   *
   * Removes the name of a worker from the set of workers that store a blob.
   */
  @ThreadSafe
  void removeBlobLocation(Digest blobDigest, String workerName) throws IOException;

  /**
   * The CAS is represented as a map where the key is the digest of the blob
   * that is being stored and the value is a set of the names of the workers
   * where that blob is stored.
   *
   * Removes the name of a worker from the set of workers that store multiple blobs.
   */
  @ThreadSafe
  void removeBlobsLocation(Iterable<Digest> blobDigests, String workerName) throws IOException;

  /**
   * The CAS is represented as a map where the key is the digest of the blob
   * that is being stored and the value is a set of the names of the workers
   * where that blob is stored.
   *
   * Returns a random worker from the set of workers that store a blob.
   */
  @ThreadSafe
  String getBlobLocation(Digest blobDigest) throws IOException;

  /**
   * The CAS is represented as a map where the key is the digest of the blob
   * that is being stored and the value is a set of the names of the workers
   * where that blob is stored.
   *
   * Returns the set of the names of all workers that store a blob.
   */
  @ThreadSafe
  Set<String> getBlobLocationSet(Digest blobDigest) throws IOException;

  @ThreadSafe
  Map<Digest, Set<String>> getBlobDigestsWorkers(Iterable<Digest> blobDigests) throws IOException;

  /**
   * Operations are stored in a hash map where the key is the name of the
   * operation and the value is the actual Operation object.
   *
   * Retrieves and returns an operation from the hash map.
   */
  @ThreadSafe
  Operation getOperation(String operationName) throws IOException;

  /**
   * Operations are stored in a hash map where the key is the name of the
   * operation and the value is the actual Operation object.
   *
   * Stores an operation in the hash map.
   */
  @ThreadSafe
  boolean putOperation(Operation operation, Stage stage) throws IOException;

  @ThreadSafe
  ExecuteEntry deprequeueOperation() throws IOException, InterruptedException;

  /**
   * The state of operations is tracked in a series of lists representing the
   * order in which the work is to be processed (queued, dispatched, and
   * completed).
   *
   * Moves an operation from the list of queued operations to the list of
   * dispatched operations.
   */
  @ThreadSafe
  QueueEntry dispatchOperation() throws IOException, InterruptedException;

  /**
   * Updates the backplane to indicate that the operation is being
   * queued and should not be considered immediately lost
   */
  @ThreadSafe
  void queueing(String operationName) throws IOException;

  /**
   * The state of operations is tracked in a series of lists representing the
   * order in which the work is to be processed (queued, dispatched, and
   * completed).
   *
   * Updates a dispatchedOperation requeue_at and returns whether the
   * operation is still valid.
   */
  @ThreadSafe
  boolean pollOperation(QueueEntry queueEntry, Stage stage, long requeueAt) throws IOException;

  /**
   * Complete an operation
   */
  @ThreadSafe
  void completeOperation(String operationName) throws IOException;

  /**
   * Delete an operation
   */
  @ThreadSafe
  void deleteOperation(String operationName) throws IOException;

  /**
   * Register a watcher for an operation
   */
  @ThreadSafe
  ListenableFuture<Void> watchOperation(String operationName, Watcher watcher) throws IOException;

  /**
   * Get all dispatched operations
   */
  @ThreadSafe
  ImmutableList<DispatchedOperation> getDispatchedOperations() throws IOException;

  /**
   * Get all operations
   */
  @ThreadSafe
  Iterable<String> getOperations() throws IOException;

  /**
   * Requeue a dispatched operation
   */
  @ThreadSafe
  void requeueDispatchedOperation(QueueEntry queueEntry) throws IOException;


  @ThreadSafe
  void prequeue(ExecuteEntry executeEntry, Operation operation) throws IOException;

  @ThreadSafe
  void queue(QueueEntry queueEntry, Operation operation) throws IOException;

  /**
   * Store a directory tree and all of its descendants
   */
  @ThreadSafe
  void putTree(Digest inputRoot, Iterable<Directory> directories) throws IOException;

  /**
   * Retrieve a directory tree and all of its descendants
   */
  @ThreadSafe
  Iterable<Directory> getTree(Digest inputRoot) throws IOException;

  /**
   * Destroy a cached directory tree of a completed operation
   */
  @ThreadSafe
  void removeTree(Digest inputRoot) throws IOException;

  /**
   * Page through action cache
   */
  @ThreadSafe
  ActionCacheScanResult scanActionCache(String scanToken, int count) throws IOException;

  /**
   * Test for whether an operation may be queued
   */
  @ThreadSafe
  boolean canQueue() throws IOException;

  /**
   * Test for whether an operation may be prequeued
   */
  @ThreadSafe
  boolean canPrequeue() throws IOException;
}
