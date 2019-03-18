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

package build.buildfarm.worker;

import static build.buildfarm.worker.Utils.readdir;
import static build.buildfarm.worker.Utils.removeDirectory;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.util.concurrent.Futures.allAsList;
import static com.google.common.util.concurrent.Futures.catchingAsync;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.transform;
import static com.google.common.util.concurrent.Futures.transformAsync;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;
import static java.lang.String.format;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Level.SEVERE;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FileNode;
import build.buildfarm.cas.ContentAddressableStorage;
import build.buildfarm.common.DigestUtil;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.protobuf.ByteString;
import io.grpc.Deadline;
import io.grpc.StatusRuntimeException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.logging.Logger;

public abstract class CASFileCache implements ContentAddressableStorage {
  private static final Logger logger = Logger.getLogger(CASFileCache.class.getName());

  private final Path root;
  private final long maxSizeInBytes;
  private final DigestUtil digestUtil;
  private final ConcurrentMap<Path, Entry> storage;
  private final Map<Digest, DirectoryEntry> directoryStorage = Maps.newHashMap();
  private final LockMap locks = new LockMap();
  private final Consumer<Digest> onPut;
  private final Consumer<Iterable<Digest>> onExpire;
  private final ExecutorService expireService;

  private ListenableFuture<Path> lastPutDirectory = immediateFuture(null);

  private transient long sizeInBytes = 0;
  private transient Entry header = new SentinelEntry();

  public CASFileCache(
      Path root,
      long maxSizeInBytes,
      DigestUtil digestUtil,
      ExecutorService expireService) {
    this(
        root,
        maxSizeInBytes,
        digestUtil,
        expireService,
        /* storage=*/ Maps.newConcurrentMap(),
        /* onPut=*/ (digest) -> {},
        /* onExpire=*/ (digests) -> {});
  }

  public CASFileCache(
      Path root,
      long maxSizeInBytes,
      DigestUtil digestUtil,
      ExecutorService expireService,
      ConcurrentMap<Path, Entry> storage,
      Consumer<Digest> onPut,
      Consumer<Iterable<Digest>> onExpire) {
    this.root = root;
    this.maxSizeInBytes = maxSizeInBytes;
    this.digestUtil = digestUtil;
    this.expireService = expireService;
    this.storage = storage;
    this.onPut = onPut;
    this.onExpire = onExpire;

    header.before = header.after = header;
  }

  public static <T> T getInterruptiblyOrIOException(ListenableFuture<T> future) throws IOException, InterruptedException {
    try {
      return future.get();
    } catch (ExecutionException e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      if (e.getCause() instanceof InterruptedException) {
        throw (InterruptedException) e.getCause();
      }
      throw new UncheckedExecutionException(e.getCause());
    }
  }

  private static Digest keyToDigest(Path key, DigestUtil digestUtil) throws NumberFormatException {
    String fileName = key.getFileName().toString();
    String[] components = fileName.split("_");

    String hashComponent = components[0];
    String sizeComponent = components[1];
    int parsedSizeComponent = Integer.parseInt(sizeComponent);

    return digestUtil.build(hashComponent, parsedSizeComponent);
  }

  /**
   * Parses the given fileName and invokes the onKey method if successful
   *
   * if size > 0, consider the filename invalid if it does not match
   */
  private static <T> T parseFileEntryKey(String fileName, long size, DigestUtil digestUtil, BiFunction<Digest, Boolean, T> onKey) {
    String[] components = fileName.split("_");
    if (components.length < 2 || components.length > 3) {
      return null;
    }

    boolean isExecutable = false;
    Digest digest;
    try {
      String sizeComponent = components[1];
      int parsedSizeComponent = Integer.parseInt(sizeComponent);

      if (size > 0 && parsedSizeComponent != size) {
        return null;
      }

      String hashComponent = components[0];
      digest = digestUtil.build(hashComponent, parsedSizeComponent);
      if (components.length == 3) {
        if (components[2].equals("exec")) {
          isExecutable = true;
        } else {
          return null;
        }
      }
    } catch (NumberFormatException e) {
      return null;
    }

    return onKey.apply(digest, isExecutable);
  }

  private FileEntryKey parseFileEntryKey(String fileName) {
    return parseFileEntryKey(fileName, /* size=*/ -1);
  }

  private FileEntryKey parseFileEntryKey(String fileName, long size) {
    return parseFileEntryKey(
        fileName,
        size,
        digestUtil,
        (digest, isExecutable) -> new FileEntryKey(getKey(digest, isExecutable), isExecutable, digest));
  }

  private boolean contains(Digest digest, boolean isExecutable) throws IOException, InterruptedException {
    Path key = getKey(digest, isExecutable);
    Lock l = locks.acquire(key);
    if (l.tryLock()) {
      try {
        Entry e = storage.get(key);
        if (e == null) {
          return false;
        }
        synchronized (this) {
          if (!entryExists(e)) {
            Entry removedEntry = storage.remove(key);
            if (removedEntry != null) {
              unlinkEntry(removedEntry);
            }
            return false;
          }
          e.recordAccess(header);
          return true;
        }
      } finally {
        l.unlock();
      }
    } else {
      // can't preserve the integrity, but if another of the contains has the lock, it is doing so
      return storage.get(key) != null;
    }
  }

  private boolean entryExists(Entry e) {
    if (!e.existsDeadline.isExpired()) {
      return true;
    }

    if (Files.exists(e.key)) {
      e.existsDeadline = Deadline.after(10, SECONDS);
      return true;
    }
    return false;
  }

  @Override
  public Iterable<Digest> findMissingBlobs(Iterable<Digest> digests) throws InterruptedException {
    ImmutableList.Builder<Digest> missingDigests = ImmutableList.builder();
    for (Digest digest : digests) {
      if (!contains(digest)) {
        missingDigests.add(digest);
      }
    }
    return missingDigests.build();
  }

  private boolean contains(Digest digest) throws InterruptedException {
    try {
      /* maybe swap the order here if we're higher in ratio on one side */
      return contains(digest, false) || contains(digest, true);
    } catch (IOException e) {
      logger.log(SEVERE, "error testing contains for " + DigestUtil.toString(digest), e);
      return false;
    }
  }

  @Override
  public InputStream newInput(Digest digest, long offset) throws IOException, InterruptedException {
    logger.fine(format("getting input stream for %s", DigestUtil.toString(digest)));
    boolean isExecutable = false;
    do {
      Path key = getKey(digest, isExecutable);
      Entry e = storage.get(key);
      if (e != null) {
        InputStream input = null;
        try {
          input = Files.newInputStream(key);
          input.skip(offset);
        } catch (NoSuchFileException eNoEnt) {
          synchronized (this) {
            Entry removedEntry = storage.remove(key);
            if (removedEntry == e) {
              unlinkEntry(removedEntry);
            }
          }
          if (isExecutable) {
            onExpire.accept(ImmutableList.of(digest));
          }
          continue;
        }
        synchronized (this) {
          e.recordAccess(header);
        }
        return input;
      }
      isExecutable = !isExecutable;
    } while (isExecutable != false);
    throw new NoSuchFileException(DigestUtil.toString(digest));
  }

  @Override
  public Blob get(Digest digest) {
    int retries = 5;
    while (retries > 0) {
      try {
        try (InputStream input = newInput(digest, 0)) {
          ByteString content = ByteString.readFrom(input);
          return new Blob(content, digest);
        }
      } catch (IOException e) {
        logger.log(SEVERE, "error getting " + DigestUtil.toString(digest), e);
        retries--;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
    logger.severe(format("CASFileCache::get(%s): exceeded IOException retry", DigestUtil.toString(digest)));
    return null;
  }

  @Override
  public void put(Blob blob) throws InterruptedException {
    Path blobPath = getKey(blob.getDigest(), false);
    try {
      logger.fine(format("put: %s", blobPath.getFileName()));
      OutputStream out = putImpl(
          blobPath,
          blob.getDigest().getSizeBytes(),
          /* isExecutable=*/ false,
          null,
          () -> onPut.accept(blob.getDigest()));
      try {
        if (out != null) {
          try {
            blob.getData().writeTo(out);
          } finally {
            out.close();
          }
        }
      } finally {
        decrementReference(blobPath);
      }
    } catch (IOException e) {
      logger.log(SEVERE, "error putting " + DigestUtil.toString(blob.getDigest()), e);
    }
  }

  @Override
  public OutputStream newOutput(Digest digest) throws IOException {
    Path blobPath = getKey(digest, false);
    final OutputStream out;
    try {
      logger.fine(format("newOutput: %s", blobPath.getFileName()));
      out = putImpl(
          blobPath,
          digest.getSizeBytes(),
          /* isExecutable=*/ false,
          null,
          () -> onPut.accept(digest));
    } catch (InterruptedException e) {
      throw new IOException(e);
    }
    if (out == null) {
      decrementReference(blobPath);
      return null;
    }
    return new OutputStream() {
      AtomicBoolean closed = new AtomicBoolean(false);

      @Override
      public void close() throws IOException {
        if (closed.compareAndSet(/* expected=*/ false, /* update=*/ true)) {
          out.close();

          decrementReference(blobPath);
        }
      }

      @Override
      public void flush() throws IOException {
        out.flush();
      }

      @Override
      public void write(byte[] b) throws IOException {
        out.write(b);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
      }

      @Override
      public void write(int b) throws IOException {
        out.write(b);
      }
    };
  }

  @Override
  public void put(Blob blob, Runnable onExpiration) {
    throw new UnsupportedOperationException();
  }

  private static final class SharedLock implements Lock {
    private final AtomicBoolean locked = new AtomicBoolean(false);

    @Override
    public void lock() {
      for (;;) {
        try {
          lockInterruptibly();
          return;
        } catch (InterruptedException e) {
          // ignore
        }
      }
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
      // attempt to atomically synchronize
      synchronized (locked) {
        while (!locked.compareAndSet(/* expected=*/ false, /* update=*/ true)) {
          locked.wait();
        }
      }
    }

    @Override
    public Condition newCondition() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean tryLock() {
      synchronized (locked) {
        return locked.compareAndSet(false, true);
      }
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void unlock() {
      if (!locked.compareAndSet(/* expected=*/ true, /* update=*/ false)) {
        throw new IllegalMonitorStateException("the lock was not held");
      }
      synchronized (locked) {
        locked.notify();
      }
    }
  }

  private static final class LockMap {
    private final Map<Path, Lock> mutexes = Maps.newHashMap();

    private synchronized Lock acquire(Path key) {
      Lock mutex = mutexes.get(key);
      if (mutex == null) {
        mutex = new SharedLock();
        mutexes.put(key, mutex);
      }
      return mutex;
    }

    private synchronized void release(Path key) {
      // prevents this lock from being exclusive to other accesses, since it
      // must now be present
      mutexes.remove(key);
    }
  }

  private static final class FileEntryKey {
    private final Path key;
    private final boolean isExecutable;
    private final Digest digest;

    FileEntryKey(Path key, boolean isExecutable, Digest digest) {
      this.key = key;
      this.isExecutable = isExecutable;
      this.digest = digest;
    }

    Path getKey() {
      return key;
    }

    boolean getIsExecutable() {
      return isExecutable;
    }

    Digest getDigest() {
      return digest;
    }
  }

  private Directory computeDirectory(
      Path path,
      Map<Object, Entry> fileKeys,
      ImmutableList.Builder<Path> inputsBuilder) {
    Directory.Builder b = Directory.newBuilder();

    List<Dirent> sortedDirent;
    try {
      sortedDirent = readdir(path, /* followSymlinks= */ false);
    } catch (IOException e) {
      logger.log(SEVERE, "error reading directory " + path.toString(), e);
      return null;
    }
    sortedDirent.sort(Comparator.comparing(Dirent::getName));

    for (Dirent dirent : sortedDirent) {
      String name = dirent.getName();
      Path child = path.resolve(name);
      if (dirent.getType() == Dirent.Type.DIRECTORY) {
        Directory dir = computeDirectory(child, fileKeys, inputsBuilder);
        if (dir == null) {
          return null;
        }
        b.addDirectoriesBuilder().setName(name).setDigest(digestUtil.compute(dir));
      } else if (dirent.getType() == Dirent.Type.FILE) {
        Entry e = fileKeys.get(dirent.getStat().fileKey());
        if (e == null) {
          return null;
        }
        checkNotNull(e.key);
        inputsBuilder.add(e.key);
        Digest digest = CASFileCache.keyToDigest(e.key, digestUtil);
        b.addFilesBuilder().setName(name).setDigest(digest).setIsExecutable(Files.isExecutable(child));
      } else {
        return null;
      }
    }

    return b.build();
  }

  public void start() throws IOException, InterruptedException {
    start(newDirectExecutorService());
  }

  public void start(ExecutorService removeDirectoryService) throws IOException, InterruptedException {
    start(onPut, removeDirectoryService);
  }

  /**
   * initialize the cache for persistent storage and inject any
   * consistent entries which already exist under the root into
   * the storage map. This call will create the root if it does
   * not exist, and will scale in cost with the number of files
   * already present.
   */
  public void start(Consumer<Digest> onPut, ExecutorService removeDirectoryService) throws IOException, InterruptedException {
    Files.createDirectories(root);

    ImmutableList.Builder<Path> directories = new ImmutableList.Builder<>();
    ImmutableMap.Builder<Object, Entry> fileKeysBuilder = new ImmutableMap.Builder<>();
    Files.walkFileTree(root, ImmutableSet.<FileVisitOption>of(), 1, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (attrs.isDirectory()) {
          directories.add(file);
          return FileVisitResult.CONTINUE;
        }

        long size = attrs.size();
        if (sizeInBytes + size > maxSizeInBytes) {
          Files.delete(file);
        } else {
          FileEntryKey fileEntryKey = null;
          if (file.getParent().equals(root)) {
            String fileName = file.getFileName().toString();
            fileEntryKey = parseFileEntryKey(fileName, size);
          }
          if (fileEntryKey != null) {
            Path key = fileEntryKey.getKey();
            Lock l = locks.acquire(key);
            l.lock();
            try {
              if (storage.get(key) == null) {
                long now = System.nanoTime();
                Entry e = new Entry(key, size, null, Deadline.after(10, SECONDS));
                fileKeysBuilder.put(attrs.fileKey(), e);
                storage.put(e.key, e);
                onPut.accept(fileEntryKey.getDigest());
                synchronized (this) {
                  e.decrementReference(header);
                }
                sizeInBytes += size;
              }
            } finally {
              l.unlock();
            }
          } else {
            Files.delete(file);
          }
        }
        return FileVisitResult.CONTINUE;
      }
    });

    ExecutorService pool = Executors.newFixedThreadPool(
        /* nThreads=*/ 32,
        new ThreadFactoryBuilder().setNameFormat("scan-cache-pool-%d").build());

    ImmutableList.Builder<Path> invalidDirectories = new ImmutableList.Builder<>();

    Map<Object, Entry> fileKeys = fileKeysBuilder.build();
    for (Path path : directories.build()) {
      pool.execute(() -> {
        ImmutableList.Builder<Path> inputsBuilder = new ImmutableList.Builder<>();
        Directory directory = computeDirectory(path, fileKeys, inputsBuilder);
        Digest digest = directory == null ? null : digestUtil.compute(directory);
        if (digest != null && getDirectoryPath(digest).equals(path)) {
          DirectoryEntry e = new DirectoryEntry(
              directory,
              inputsBuilder.build(),
              Deadline.after(10, SECONDS));
          synchronized (this) {
            directoryStorage.put(digest, e);
            for (Path input : e.inputs) {
              Entry entry = storage.get(input);
              entry.containingDirectories.add(digest);
            }
          }
        } else {
          synchronized (invalidDirectories) {
            invalidDirectories.add(path);
          }
        }
      });
    }
    pool.shutdown();
    // FIXME exhaustion timeout?
    while (!pool.isTerminated()) {
      logger.info("Waiting for directory population to complete");
      pool.awaitTermination(1, MINUTES);
    }

    for (Path path : invalidDirectories.build()) {
      removeDirectory(path, removeDirectoryService);
    }
  }

  private static String digestFilename(Digest digest) {
    return format("%s_%d", digest.getHash(), digest.getSizeBytes());
  }

  public static String getFileName(Digest digest, boolean isExecutable) {
    return format(
        "%s%s",
        digestFilename(digest),
        (isExecutable ? "_exec" : ""));
  }

  public Path getKey(Digest digest, boolean isExecutable) {
    return getPath(getFileName(digest, isExecutable));
  }

  private void decrementReference(Path inputFile) {
    decrementReferences(ImmutableList.of(inputFile), ImmutableList.of());
  }

  public synchronized void decrementReferences(Iterable<Path> inputFiles, Iterable<Digest> inputDirectories) {
    decrementReferencesSynchronized(inputFiles, inputDirectories);
  }

  private int decrementInputReferences(Iterable<Path> inputFiles) {
    int entriesDereferenced = 0;
    for (Path input : inputFiles) {
      checkNotNull(input);
      Entry e = storage.get(input);
      if (e == null) {
        throw new IllegalStateException(input + " has been removed with references");
      }
      if (!e.key.equals(input)) {
        throw new RuntimeException("ERROR: entry retrieved: " + e.key + " != " + input);
      }
      e.decrementReference(header);
      if (e.referenceCount == 0) {
        entriesDereferenced++;
      }
    }
    return entriesDereferenced;
  }

  private void decrementReferencesSynchronized(Iterable<Path> inputFiles, Iterable<Digest> inputDirectories) {
    // decrement references and notify if any dropped to 0
    // insert after the last 0-reference count entry in list
    int entriesDereferenced = decrementInputReferences(inputFiles);
    for (Digest inputDirectory : inputDirectories) {
      DirectoryEntry dirEntry = directoryStorage.get(inputDirectory);
      if (dirEntry == null) {
        throw new IllegalStateException("inputDirectory " + DigestUtil.toString(inputDirectory) + " is not in directoryStorage");
      }
      try {
        entriesDereferenced += decrementInputReferences(dirEntry.inputs);
      } catch (IllegalStateException e) {
        System.err.println("Directory " + DigestUtil.toString(inputDirectory) + " could not be decremented");
        throw e;
      }
    }
    if (entriesDereferenced > 0) {
      notify();
    }
  }

  public Path getRoot() {
    return root;
  }

  public Path getPath(String filename) {
    return root.resolve(filename);
  }

  private void remove(Digest digest, boolean isExecutable) throws IOException, InterruptedException {
    Path key = getKey(digest, isExecutable);
    Lock l = locks.acquire(key);
    l.lockInterruptibly();
    try {
      Entry e = storage.remove(key);
      if (e != null) {
        synchronized (this) {
          unlinkEntry(e);
        }
      }
    } finally {
      l.unlock();
    }
  }

  /** must be called in synchronized context */
  private void unlinkEntry(Entry e) throws IOException, InterruptedException {
    if (e.referenceCount == 0) {
      e.unlink();
    } else {
      logger.severe("removed referenced entry " + e.key);
    }
    ImmutableList.Builder<ListenableFuture<Void>> directoryExpirationFutures = ImmutableList.builder();
    for (Digest containingDirectory : e.containingDirectories) {
      directoryExpirationFutures.add(expireDirectory(containingDirectory, expireService));
    }
    getInterruptiblyOrIOException(allAsList(directoryExpirationFutures.build()));
    // still debating this one being in this method
    sizeInBytes -= e.size;
    // technically we should attempt to remove the file here,
    // but we're only called in contexts where it doesn't exist...
  }

  @VisibleForTesting
  public Path getDirectoryPath(Digest digest) {
    return root.resolve(digestFilename(digest) + "_dir");
  }

  /** must be called in synchronized context */
  private Entry waitForLastUnreferencedEntry(long blobSizeInBytes) throws InterruptedException {
    while (header.after == header) {
      int references = 0;
      int keys = 0;
      int min = -1, max = 0;
      Path minkey = null, maxkey = null;
      logger.info(format("CASFileCache::expireEntry(%d) header(%s): { after: %s, before: %s }", blobSizeInBytes, header.hashCode(), header.after.hashCode(), header.before.hashCode()));
      // this should be incorporated in the listenable future construction...
      for (Map.Entry<Path, Entry> pe : storage.entrySet()) {
        Path key = pe.getKey();
        Entry e = pe.getValue();
        if (e.referenceCount > max) {
          max = e.referenceCount;
          maxkey = key;
        }
        if (min == -1 || e.referenceCount < min) {
          min = e.referenceCount;
          minkey = key;
        }
        if (e.referenceCount == 0) {
          logger.info(format("CASFileCache::expireEntry(%d) unreferenced entry(%s): { after: %s, before: %s }",
                blobSizeInBytes, e.hashCode(), e.after == null ? null : e.after.hashCode(), e.before == null ? null : e.before.hashCode()));
        }
        references += e.referenceCount;
        keys++;
      }
      if (keys == 0) {
        throw new IllegalStateException("CASFileCache::expireEntry(" + blobSizeInBytes + ") there are no keys to wait for expiration on");
      }
      logger.info(format(
          "CASFileCache::expireEntry(%d) unreferenced list is empty, %d bytes, %d keys with %d references, min(%d, %s), max(%d, %s)",
          blobSizeInBytes, sizeInBytes, keys, references, min, minkey, max, maxkey));
      wait();
    }
    return header.after;
  }

  /** must be called in synchronized context */
  private ListenableFuture<Path> expireEntry(
      long blobSizeInBytes,
      ExecutorService service) throws IOException, InterruptedException {
    for (;;) {
      Lock l = null;
      Entry e = null;
      while (e == null) {
        e = waitForLastUnreferencedEntry(blobSizeInBytes);
        if (e.referenceCount != 0) {
          throw new IllegalStateException("ERROR: Reference counts lru ordering has not been maintained correctly, attempting to expire referenced (or negatively counted) content " + e.key.getFileName() + " with " + e.referenceCount + " references");
        }
        l = locks.acquire(e.key);
        if (!l.tryLock()) {
          logger.info(format("cannot acquire lock for %s, lock state is %s, moving it to the head of the unreferenced list", e.key.getFileName(), l));
          e.recordAccess(header);
          e = null;
        }
      }
      try {
        Path key = e.key;
        if (storage.remove(key) == e) {
          ImmutableList.Builder<ListenableFuture<Void>> directoryExpirationFutures = ImmutableList.builder();
          for (Digest containingDirectory : e.containingDirectories) {
            directoryExpirationFutures.add(expireDirectory(containingDirectory, service));
          }
          e.unlink();
          sizeInBytes -= e.size;
          return transform(allAsList(directoryExpirationFutures.build()), (result) -> key, service);
        }
      } finally {
        l.unlock();
      }
    }
  }

  /** must be called in synchronized context */
  private void purgeDirectoryFromInputs(Digest digest, Iterable<Path> inputs) {
    for (Path input : inputs) {
      Entry fileEntry = storage.get(input);

      if (fileEntry != null) {
        fileEntry.containingDirectories.remove(digest);
      }
    }
  }

  /** must be called in synchronized context */
  private ListenableFuture<Void> expireDirectory(Digest digest, ExecutorService service) {
    DirectoryEntry e = directoryStorage.remove(digest);
    if (e == null) {
      logger.severe(format("CASFileCache::expireDirectory(%s) does not exist", DigestUtil.toString(digest)));
      return immediateFuture(null);
    }

    purgeDirectoryFromInputs(digest, e.inputs);
    return removeDirectory(getDirectoryPath(digest), service);
  }

  public Iterable<ListenableFuture<Void>> putFiles(
      Iterable<FileNode> files,
      Path path,
      ImmutableList.Builder<Path> inputsBuilder,
      ExecutorService service) throws IOException, InterruptedException {
    ImmutableList.Builder<ListenableFuture<Void>> putFutures = ImmutableList.builder();
    putDirectoryFiles(files, path, /* containingDirectory=*/ null, inputsBuilder, putFutures, service);
    return putFutures.build();
  }

  private void putDirectoryFiles(
      Iterable<FileNode> files,
      Path path,
      Digest containingDirectory,
      ImmutableList.Builder<Path> inputsBuilder,
      ImmutableList.Builder<ListenableFuture<Void>> putFutures,
      ExecutorService service) throws IOException, InterruptedException {
    for (FileNode fileNode : files) {
      Path filePath = path.resolve(fileNode.getName());
      final ListenableFuture<Void> putFuture;
      if (fileNode.getDigest().getSizeBytes() != 0) {
        Path key = getKey(fileNode.getDigest(), fileNode.getIsExecutable());
        putFuture = transformAsync(
            put(fileNode.getDigest(), fileNode.getIsExecutable(), containingDirectory, service),
            (fileCacheKey) -> {
              // FIXME this can die with 'too many links'... needs some cascading fallout
              Files.createLink(filePath, fileCacheKey);
              // we saw null entries in the built immutable list without synchronization
              synchronized (inputsBuilder) {
                inputsBuilder.add(fileCacheKey);
              }
              return immediateFuture(null);
            },
            service);
      } else {
        putFuture = listeningDecorator(service).submit(() -> {
          Files.createFile(filePath);
          // ignore executable
          return null;
        });
      }
      putFutures.add(putFuture);
    }
  }

  private void fetchDirectory(
      Digest containingDirectory,
      Path path,
      Digest digest,
      Map<Digest, Directory> directoriesIndex,
      ImmutableList.Builder<Path> inputsBuilder,
      ImmutableList.Builder<ListenableFuture<Void>> putFutures,
      ExecutorService service) throws IOException, InterruptedException {
    if (Files.exists(path)) {
      if (Files.isDirectory(path)) {
        removeDirectory(path);
      } else {
        Files.delete(path);
      }
    }
    Directory directory = directoriesIndex.get(digest);
    if (directory == null) {
      throw new IOException(
          format(
              "directory not found for %s(%s)",
              path,
              DigestUtil.toString(digest)));
    }
    Files.createDirectory(path);
    putDirectoryFiles(directory.getFilesList(), path, containingDirectory, inputsBuilder, putFutures, service);
    for (DirectoryNode directoryNode : directory.getDirectoriesList()) {
      fetchDirectory(
          containingDirectory,
          path.resolve(directoryNode.getName()),
          directoryNode.getDigest(),
          directoriesIndex,
          inputsBuilder,
          putFutures,
          service);
    }
  }

  public ListenableFuture<Path> putDirectory(
      Digest digest,
      Map<Digest, Directory> directoriesIndex,
      ExecutorService service) {
    Path path = getDirectoryPath(digest);
    Lock l = locks.acquire(path);
    logger.fine(format("locking directory %s", path.getFileName()));
    try {
      l.lockInterruptibly();
    } catch (InterruptedException e) {
      return immediateFailedFuture(e);
    }
    logger.fine(format("locked directory %s", path.getFileName()));
    ListenableFuture<Path> putFuture;
    try {
      putFuture = putDirectorySynchronized(path, digest, directoriesIndex, service);
    } catch (IOException|InterruptedException e) {
      putFuture = immediateFailedFuture(e);
    }
    putFuture.addListener(
        () -> {
          l.unlock();
          logger.fine(format("directory %s has been unlocked", path.getFileName()));
        },
        service);
    return putFuture;
  }

  private boolean directoryExists(Path path, Directory directory, Map<Digest, Directory> directoriesIndex) {
    if (!Files.exists(path)) {
      logger.severe(format("directory path %s does not exist", path));
      return false;
    }
    for (FileNode fileNode : directory.getFilesList()) {
      Path filePath = path.resolve(fileNode.getName());
      if (!Files.exists(filePath)) {
        logger.severe(format("directory file entry %s does not exist", filePath));
        return false;
      }
      // additional stat check to ensure that the cache entry exists for hard link inode match?
    }
    for (DirectoryNode directoryNode : directory.getDirectoriesList()) {
      if (!directoryExists(
            path.resolve(directoryNode.getName()),
            directoriesIndex.get(directoryNode.getDigest()),
            directoriesIndex)) {
        return false;
      }
    }
    return true;
  }

  private boolean directoryEntryExists(Path path, DirectoryEntry dirEntry, Map<Digest, Directory> directoriesIndex) {
    if (!dirEntry.existsDeadline.isExpired()) {
      return true;
    }

    if (directoryExists(path, dirEntry.directory, directoriesIndex)) {
      dirEntry.existsDeadline = Deadline.after(10, SECONDS);
      return true;
    }
    return false;
  }

  private ListenableFuture<Path> putDirectorySynchronized(
      Path path,
      Digest digest,
      Map<Digest, Directory> directoriesIndex,
      ExecutorService service) throws IOException, InterruptedException {
    logger.fine(format("directory %s has been locked", path.getFileName()));
    ListenableFuture<Void> expireFuture;
    synchronized (this) {
      DirectoryEntry e = directoryStorage.get(digest);
      if (e == null) {
        expireFuture = immediateFuture(null);
      } else {
        ImmutableList.Builder<Path> inputsBuilder = new ImmutableList.Builder<>();
        for (Path input : e.inputs) {
          Entry fileEntry = storage.get(input);
          if (fileEntry == null) {
            logger.severe(
                format(
                    "CASFileCache::putDirectory(%s) exists, but input %s does not, purging it with fire and resorting to fetch",
                    DigestUtil.toString(digest),
                    input));
            e = null;
            break;
          }
          fileEntry.incrementReference();
          checkNotNull(input);
          inputsBuilder.add(input);
        }

        if (e != null) {
          logger.fine(format("found existing entry for %s", path.getFileName()));
          if (directoryExists(path, e.directory, directoriesIndex)) {
            return immediateFuture(path);
          }
          logger.severe(format("directory %s does not exist in cache, purging it with fire and resorting to fetch", path.getFileName()));
        }

        decrementReferencesSynchronized(inputsBuilder.build(), ImmutableList.<Digest>of());
        expireFuture = expireDirectory(digest, service);
        logger.fine(format("expiring existing entry for %s", path.getFileName()));
      }
    }

    ImmutableList.Builder<Path> inputsBuilder = ImmutableList.builder();
    ListenableFuture<List<Void>> fetchFuture = transformAsync(
        expireFuture,
        (results) -> {
            logger.fine(format("expiry complete, fetching %s", path.getFileName()));
            ImmutableList.Builder<ListenableFuture<Void>> putFutures = ImmutableList.builder();
            fetchDirectory(digest, path, digest, directoriesIndex, inputsBuilder, putFutures, service);
            return catchingAsync(
                allAsList(putFutures.build()),
                Throwable.class,
                (e) -> {
                  ImmutableList<Path> inputs = inputsBuilder.build();
                  synchronized (this) {
                    purgeDirectoryFromInputs(digest, inputs);
                    decrementReferencesSynchronized(inputs, ImmutableList.<Digest>of());
                  }
                  try {
                    removeDirectory(path);
                  } catch (IOException removeException) {
                    logger.log(SEVERE, "error during directory removal after fetch failure", removeException);
                  }
                  return immediateFailedFuture(e);
                },
                service);
        },
        service);

    return transform(
        fetchFuture,
        (results) -> {
          logger.fine(format("directory fetch complete, inserting %s", path.getFileName()));
          DirectoryEntry e = new DirectoryEntry(
              directoriesIndex.get(digest),
              inputsBuilder.build(),
              Deadline.after(10, SECONDS));
          synchronized (this) {
            directoryStorage.put(digest, e);
          }
          return path;
        },
        service);
  }

  @VisibleForTesting
  public Path put(Digest digest, boolean isExecutable) throws IOException, InterruptedException {
    checkState(digest.getSizeBytes() > 0, "file entries may not be empty");

    Path key = getKey(digest, isExecutable);
    CancellableOutputStream out = putImpl(
        key,
        digest.getSizeBytes(),
        isExecutable,
        /* containingDirectory=*/ null,
        () -> onPut.accept(digest));
    if (out != null) {
      copyExternalInput(digest, out);
    }
    return key;
  }

  // This can result in deadlock if called with a direct executor. I'm unsure how to guard
  // against it, until we can get to using a current-download future
  public ListenableFuture<Path> put(
      Digest digest,
      boolean isExecutable,
      Digest containingDirectory,
      Executor executor) {
    checkState(digest.getSizeBytes() > 0, "file entries may not be empty");

    Path key = getKey(digest, isExecutable);
    return transformAsync(
        immediateFuture(null),
        (result) -> {
          CancellableOutputStream out = putImpl(
              key,
              digest.getSizeBytes(),
              isExecutable,
              containingDirectory,
              () -> onPut.accept(digest));
          if (out != null) {
            copyExternalInput(digest, out);
          }
          return immediateFuture(key);
        },
        executor);
  }

  private void copyExternalInput(Digest digest, CancellableOutputStream out) throws IOException, InterruptedException {
    try {
      logger.fine(format("downloading %s", DigestUtil.toString(digest)));
      ByteStreams.copy(newExternalInput(digest, /* offset=*/ 0), out);
      logger.fine(format("download of %s complete", DigestUtil.toString(digest)));
    } catch (IOException e) {
      out.cancel();
      logger.log(WARNING, format("error downloading %s", DigestUtil.toString(digest)), e); // prevent burial by early end of stream during close
      throw e;
    } finally {
      try {
        logger.fine(format("closing output stream for %s", DigestUtil.toString(digest)));
        out.close();
        logger.fine(format("output stream closed for %s", DigestUtil.toString(digest)));
      } catch (IOException e) {
        if (Thread.interrupted()) {
          logger.log(SEVERE, format("could not close stream for %s", DigestUtil.toString(digest)), e);
          if (e.getCause() instanceof InterruptedException) {
            throw (InterruptedException) e.getCause();
          }
          throw new InterruptedException();
        } else {
          logger.log(WARNING, format("failed output stream close for %s", DigestUtil.toString(digest)), e);
        }
        throw e;
      }
    }
  }

  @FunctionalInterface
  private static interface InputStreamSupplier {
    InputStream newInput() throws IOException, InterruptedException;
  }

  @FunctionalInterface
  private static interface IORunnable {
    void run() throws IOException;
  }

  private static abstract class CancellableOutputStream extends OutputStream {
    void cancel() throws IOException {
    }
  }

  private static final CancellableOutputStream DUPLICATE_OUTPUT_STREAM = new CancellableOutputStream() {
    @Override
    public void write(int b) {
    }
  };

  private CancellableOutputStream putImpl(
      Path key,
      long blobSizeInBytes,
      boolean isExecutable,
      Digest containingDirectory,
      Runnable onInsert) throws IOException, InterruptedException {
    CancellableOutputStream out = null;
    Lock l = locks.acquire(key);
    logger.fine(format("locking blob %s", key.getFileName()));
    l.lockInterruptibly();
    try {
      logger.fine(format("lock for blob %s received, getting output stream", key.getFileName()));
      out = putImplSynchronized(
          key,
          blobSizeInBytes,
          isExecutable,
          containingDirectory,
          onInsert);
    } finally {
      if (out == null || out == DUPLICATE_OUTPUT_STREAM) {
        l.unlock();
        logger.fine(format("blob %s has been unlocked for duplicate", key.getFileName()));
      }
    }
    if (out == DUPLICATE_OUTPUT_STREAM) {
      return null;
    }
    logger.fine(format("entry %s is missing, downloading and populating", key.getFileName()));
    return newLockedCancellableOutputStream(
        out,
        () -> {
          l.unlock();
          logger.fine(format("blob %s has been unlocked for complete", key.getFileName()));
        });
  }

  private CancellableOutputStream newLockedCancellableOutputStream(CancellableOutputStream out, Runnable onUnlock) {
    return new CancellableOutputStream() {
      boolean terminated = false;

      @Override
      public void cancel() throws IOException {
        withSingleTermination(out::cancel);
      }

      @Override
      public void close() throws IOException {
        withSingleTermination(out::close);
      }

      private void withSingleTermination(IORunnable runnable) throws IOException {
        if (!terminated) {
          withTermination(runnable);
          terminated = true;
        }
      }

      private void withTermination(IORunnable runnable) throws IOException {
        try {
          runnable.run();
        } finally {
          onUnlock.run();
        }
      }

      @Override
      public void flush() throws IOException {
        out.flush();
      }

      @Override
      public void write(byte[] b) throws IOException {
        out.write(b);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
      }

      @Override
      public void write(int b) throws IOException {
        out.write(b);
      }
    };
  }

  // must have key locked
  private CancellableOutputStream putImplSynchronized(
      Path key,
      long blobSizeInBytes,
      boolean isExecutable,
      Digest containingDirectory,
      Runnable onInsert)
      throws IOException, InterruptedException {
    final ListenableFuture<Set<Digest>> expiredDigestsFuture;

    synchronized (this) {
      Entry e = storage.get(key);
      if (e != null && !entryExists(e)) {
        Entry removedEntry = storage.remove(key);
        if (removedEntry != null) {
          unlinkEntry(removedEntry);
        }
        e = null;
      }

      if (e != null) {
        if (containingDirectory != null) {
          e.containingDirectories.add(containingDirectory);
        }
        e.incrementReference();
        return DUPLICATE_OUTPUT_STREAM;
      }

      sizeInBytes += blobSizeInBytes;

      ImmutableList.Builder<ListenableFuture<Digest>> expiredKeysFutures = ImmutableList.builder();
      while (sizeInBytes > maxSizeInBytes) {
        expiredKeysFutures.add(
            transformAsync(
                expireEntry(blobSizeInBytes, expireService),
                (expiredKey) -> {
                  try {
                    Files.delete(expiredKey);
                  } catch (NoSuchFileException eNoEnt) {
                    logger.severe(format("CASFileCache::putImpl: expired key %s did not exist to delete", expiredKey.toString()));
                  }
                  String fileName = expiredKey.getFileName().toString();
                  FileEntryKey fileEntryKey = parseFileEntryKey(fileName);
                  if (fileEntryKey == null) {
                    logger.severe(format("error parsing expired key %s", expiredKey));
                  } else if (storage.containsKey(getKey(fileEntryKey.getDigest(), !fileEntryKey.getIsExecutable()))) {
                    return immediateFuture(null);
                  }
                  return immediateFuture(fileEntryKey.getDigest());
                },
                expireService));
      }
      expiredDigestsFuture = transform(
          allAsList(expiredKeysFutures.build()),
          (digests) -> ImmutableSet.copyOf(Iterables.filter(digests, (digest) -> digest != null)),
          expireService);
    }

    Set<Digest> expiredDigests = getInterruptiblyOrIOException(expiredDigestsFuture);
    if (!expiredDigests.isEmpty()) {
      onExpire.accept(expiredDigests);
    }

    Path tmpPath = key.resolveSibling(key.getFileName() + ".tmp");
    return new CancellableOutputStream() {
      OutputStream tmpFile = Files.newOutputStream(tmpPath, CREATE, TRUNCATE_EXISTING);
      long size = 0;

      @Override
      public void flush() throws IOException {
        tmpFile.flush();
      }

      @Override
      public void write(byte[] b) throws IOException {
        tmpFile.write(b);
        size += b.length;
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        tmpFile.write(b, off, len);
        size += len;
      }

      @Override
      public void write(int b) throws IOException {
        tmpFile.write(b);
        size++;
      }

      @Override
      public void cancel() throws IOException {
        tmpFile.close();
        Files.delete(tmpPath);
      }

      @Override
      public void close() throws IOException {
        tmpFile.close();

        if (size != blobSizeInBytes) {
          Files.delete(tmpPath);
          throw new IOException("blob digest size mismatch, expected " + blobSizeInBytes + ", was " + size);
        }

        setPermissions(tmpPath, isExecutable);
        Files.move(tmpPath, key, REPLACE_EXISTING);

        Entry entry = new Entry(
            key,
            blobSizeInBytes,
            containingDirectory,
            Deadline.after(10, SECONDS));

        if (storage.put(key, entry) != null) {
          throw new IllegalStateException("storage conflict with existing key for " + key);
        }
        try {
          onInsert.run();
        } catch (RuntimeException e) {
          throw new IOException(e);
        }
      }
    };
  }

  private static void setPermissions(Path path, boolean isExecutable) throws IOException {
    new File(path.toString()).setExecutable(isExecutable, true);
  }

  @VisibleForTesting
  public static class Entry {
    Entry before, after;
    final Path key;
    final long size;
    final Set<Digest> containingDirectories;
    int referenceCount;
    Deadline existsDeadline;

    private Entry() {
      key = null;
      size = -1;
      containingDirectories = null;
      referenceCount = -1;
      existsDeadline = null;
    }

    public Entry(Path key, long size, Digest containingDirectory, Deadline existsDeadline) {
      this.key = key;
      this.size = size;
      referenceCount = 1;
      containingDirectories = Sets.newHashSet();
      if (containingDirectory != null) {
        containingDirectories.add(containingDirectory);
      }
      this.existsDeadline = existsDeadline;
    }

    public void unlink() {
      before.after = after;
      after.before = before;
      before = null;
      after = null;
    }

    protected void addBefore(Entry existingEntry) {
      after = existingEntry;
      before = existingEntry.before;
      before.after = this;
      after.before = this;
    }

    public void incrementReference() {
      if (referenceCount < 0) {
        throw new IllegalStateException("entry " + key + " has " + referenceCount + " references and is being incremented...");
      }
      if (referenceCount == 0) {
        if (before == null || after == null) {
          throw new IllegalStateException("entry " + key + " has a broken link (" + before + ", " + after + ") and is being incremented");
        }
        unlink();
      }
      referenceCount++;
    }

    public void decrementReference(Entry header) {
      if (referenceCount == 0) {
        throw new IllegalStateException("entry " + key + " has 0 references and is being decremented...");
      }
      referenceCount--;
      if (referenceCount == 0) {
        addBefore(header);
      }
    }

    public void recordAccess(Entry header) {
      if (referenceCount == 0) {
        if (before == null || after == null) {
          throw new IllegalStateException("entry " + key + " has a broken link (" + before + ", " + after + ") and is being recorded");
        }
        unlink();
        addBefore(header);
      }
    }
  }

  private static class SentinelEntry extends Entry {
    @Override
    public void unlink() {
      throw new UnsupportedOperationException("sentinal cannot be unlinked");
    }

    @Override
    protected void addBefore(Entry existingEntry) {
      throw new UnsupportedOperationException("sentinal cannot be added");
    }

    @Override
    public void incrementReference() {
      throw new UnsupportedOperationException("sentinal cannot be referenced");
    }

    @Override
    public void decrementReference(Entry header) {
      throw new UnsupportedOperationException("sentinal cannot be referenced");
    }

    @Override
    public void recordAccess(Entry header) {
      throw new UnsupportedOperationException("sentinal cannot be accessed");
    }
  }

  private static class DirectoryEntry {
    public final Directory directory;
    public final Iterable<Path> inputs;
    Deadline existsDeadline;

    public DirectoryEntry(Directory directory, Iterable<Path> inputs, Deadline existsDeadline) {
      this.directory = directory;
      this.inputs = inputs;
      this.existsDeadline = existsDeadline;
    }
  }

  protected abstract InputStream newExternalInput(Digest digest, long offset) throws IOException, InterruptedException;
}
