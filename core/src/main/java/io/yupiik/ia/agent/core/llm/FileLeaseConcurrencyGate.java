/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.ia.agent.core.llm;

import io.yupiik.llm.client.spi.ConcurrencyGate;
import io.yupiik.llm.client.spi.InMemoryGate;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Concurrency gate sharing a provider budget across the {@code yca} processes of one machine.
 *
 * <p>An account-wide {@code 429} ({@code "Too many concurrent requests (limit: 2)"}) applies to every
 * session, not to one JVM: a purely in-memory gate multiplies the budget by the number of running
 * processes (two sessions at {@code maxConcurrent = 2} issue four concurrent requests) and the
 * provider rejects the excess. This gate keeps one lease file per slot, under
 * {@code <storageHome>/llm-concurrency/<providerId>/slot-<n>.lock}, and takes an exclusive
 * {@link FileLock} on a slot for the duration of the request: the OS makes the lease exclusive across
 * processes and - crucially - drops it when its holder dies, so there is no stale-slot recovery, no
 * pid bookkeeping and no time-to-live to tune.</p>
 *
 * <h2>Why not a POSIX semaphore</h2>
 * <p>A named semaphore ({@code sem_open}, reachable through the FFM API) is the textbook primitive
 * here, but it is not crash-resilient: a process killed while holding a count leaves the semaphore
 * permanently decremented - the kernel has no owner to give the token back to - which bricks the
 * budget until an operator removes it. Record locks are released by the kernel on process death, so
 * the simpler file-lease implementation is also the more robust one. That trade-off is deliberate:
 * leases cost a few {@code open}/{@code tryLock} syscalls per request, which is noise next to an LLM
 * round-trip.</p>
 *
 * <h2>Platform requirements</h2>
 * <p>Record locks are advisory and reliable on a local POSIX filesystem; on NFS/CIFS they may be
 * ignored or coalesced by the client. Since the storage home can be network-mounted, every filesystem
 * failure degrades this gate to an in-memory budget (logged once) instead of failing requests: the
 * protection is lost, the sessions keep working.</p>
 *
 * <h2>Implementation notes</h2>
 * <ul>
 * <li>{@link FileLock} is a per-JVM resource: two gates of the same process targeting the same slot
 *     raise {@link OverlappingFileLockException}. A static per-canonical-path guard is therefore taken
 *     before opening the channel, and slot files are never deleted - a recreated inode would be a
 *     different lock domain, so the new file would look free while an old lease is still held.</li>
 * <li>acquire never blocks the caller: a free slot is granted synchronously, otherwise the waiter is
 *     queued and a dedicated daemon thread retries the leases with a jittered backoff (the shared
 *     application scheduler is never borrowed: it runs the retry waits of the same requests).</li>
 * <li>the learned capacity of the provider is persisted in a sibling {@code capacity} file so a
 *     restart does not have to burn a {@code 429} to rediscover a smaller limit; the file expires
 *     after a time-to-live so a transient provider hiccup does not cap the budget forever.</li>
 * </ul>
 */
public class FileLeaseConcurrencyGate implements ConcurrencyGate, AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(FileLeaseConcurrencyGate.class.getName());
    private static final Cleaner CLEANER = Cleaner.create();

    /**
     * Guards the slot files of the same path inside this JVM: {@link FileLock} is process-scoped.
     * A semaphore, not a {@link java.util.concurrent.locks.ReentrantLock}: a lease is released from
     * the poller thread when a stream ends, and an intrinsic-style lock would then be unlocked by a
     * thread which does not own it.
     */
    private static final ConcurrentHashMap<Path, Semaphore> JVM_GUARDS = new ConcurrentHashMap<>();

    /** How long a persisted capacity stays authoritative before it is ignored as stale. */
    private static final long CAPACITY_TTL_MILLIS = TimeUnit.HOURS.toMillis(24);

    private static final long MIN_POLL_MS = 25L;
    private static final long MAX_POLL_MS = 250L;

    private final Path directory;
    private final int configured;
    private final Deque<Waiter> waiters = new ArrayDeque<>();
    /** Leases handed out and not returned yet: released by close() so a dir is not locked out. */
    private final List<Runnable> outstanding = new ArrayList<>();

    private int capacity;
    private int inFlight;
    private volatile boolean closed;
    private volatile InMemoryGate fallback;
    private Thread poller;

    /**
     * Creates a gate.
     *
     * @param directory the provider lease directory, e.g.
     *     {@code <storageHome>/llm-concurrency/openai}; created on the first acquire.
     * @param capacity the configured slot count, clamped to {@code >= 1}.
     */
    public FileLeaseConcurrencyGate(final Path directory, final int capacity) {
        this.directory = directory.toAbsolutePath().normalize();
        this.configured = Math.max(1, capacity);
        final var persisted = readPersistedCapacity();
        this.capacity = persisted == null || persisted >= this.configured ? this.configured : persisted;
    }

    /** The provider lease directory, mostly for diagnostics. */
    public Path directory() {
        return directory;
    }

    @Override
    public CompletionStage<Runnable> acquire() {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("concurrency gate is closed"));
        }
        final var degraded = fallback;
        if (degraded != null) {
            return degraded.acquire();
        }
        final var waiter = new Waiter();
        final Runnable granted;
        synchronized (waiters) {
            // close() may have won the race between the volatile read above and the monitor: refuse
            // admissions after a shutdown even when the entry check passed
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("concurrency gate is closed"));
            }
            // a free slot is leased synchronously: the uncontended case (the common one) must not pay
            // a poll cycle
            granted = waiter.leaseNow();
            if (granted == null && fallback != null) {
                // the attempt itself degraded the gate: serve this waiter from the in-memory budget
                // instead of making it wait for the poller
                return fallback.acquire();
            }
            if (granted == null) {
                waiters.addLast(waiter);
                waiter.granted.whenComplete((release, error) -> {
                    if (release == null) {
                        synchronized (waiters) {
                            waiters.remove(waiter);
                        }
                    }
                });
                startPoller();
                return waiter.granted;
            }
        }
        final var result = new CompletableFuture<Runnable>();
        result.complete(granted);
        return result;
    }

    @Override
    public void setCapacity(final int capacity) {
        final var bound = Math.max(1, capacity);
        final var degraded = fallback;
        if (degraded != null) {
            degraded.setCapacity(bound);
            return;
        }
        final boolean changed;
        synchronized (waiters) {
            changed = this.capacity != bound;
            this.capacity = bound;
            // a shrink only applies to future leases: a stream can run for minutes and is never
            // preempted, so the outstanding leases are absorbed as they come back
        }
        if (changed) {
            persistCapacity(bound);
            startPoller();
        }
    }

    @Override
    public void close() {
        synchronized (waiters) {
            if (closed) {
                return;
            }
            closed = true;
            for (final var waiter : waiters) {
                waiter.granted.completeExceptionally(new IllegalStateException("concurrency gate is closed"));
            }
            waiters.clear();
            // returning the outstanding leases matters beyond politeness: the in-JVM guard and the
            // record lock would otherwise keep the slot files of this directory unusable for any
            // other gate of the process
            for (final var release : List.copyOf(outstanding)) {
                release.run();
            }
            outstanding.clear();
        }
        final var thread = poller;
        if (thread != null) {
            thread.interrupt();
        }
        final var degraded = fallback;
        if (degraded != null) {
            degraded.close();
        }
    }

    /** Starts the single daemon poller retrying the leases of the queued waiters. */
    private void startPoller() {
        synchronized (waiters) {
            if (poller != null || closed) {
                return;
            }
            poller = Thread.ofPlatform()
                    .name("yca-concurrency-" + directory.getFileName())
                    .daemon(true)
                    .unstarted(this::poll);
        }
        poller.start();
    }

    private void poll() {
        var backoff = MIN_POLL_MS;
        while (!closed) {
            try {
                final boolean work;
                synchronized (waiters) {
                    work = !waiters.isEmpty();
                    if (work) {
                        drain();
                    }
                }
                if (!work) {
                    Thread.sleep(MAX_POLL_MS); // idle: nothing queued, check again softly
                    backoff = MIN_POLL_MS;
                    continue;
                }
                if (!waiters.isEmpty()) {
                    final var jitter = ThreadLocalRandom.current().nextLong(backoff / 2, backoff + 1);
                    Thread.sleep(Math.max(1L, jitter));
                    backoff = Math.min(MAX_POLL_MS, backoff * 2);
                } else {
                    backoff = MIN_POLL_MS;
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (final RuntimeException e) {
                LOGGER.log(Level.FINE, e, () -> "concurrency lease poll failed for " + directory);
            }
        }
    }

    /** Leases as many queued waiters as the capacity allows, head first (strict FIFO). */
    private void drain() {
        while (inFlight < capacity && !waiters.isEmpty()) {
            final var head = waiters.peekFirst();
            if (head.granted.isDone()) {
                waiters.pollFirst();
                continue;
            }
            final var release = head.leaseNow();
            if (release == null) {
                return; // every remaining slot is leased by a peer: wait for a release
            }
            waiters.pollFirst();
            if (!head.granted.complete(release)) {
                // the caller cancelled the grant concurrently: the OS lease is real, hand it back
                // (idempotent) so the slot of a dead caller is not leaked
                release.run();
            }
        }
    }

    private Integer readPersistedCapacity() {
        final var file = directory.resolve("capacity");
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            final var age =
                    System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis();
            if (age > CAPACITY_TTL_MILLIS) {
                return null; // stale: a provider hiccup must not cap the budget forever
            }
            final var value = Files.readString(file, StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : Integer.parseInt(value);
        } catch (final IOException | NumberFormatException e) {
            LOGGER.log(Level.FINE, e, () -> "ignoring unreadable concurrency capacity file " + file);
            return null;
        }
    }

    private void persistCapacity(final int value) {
        if (value >= configured) {
            // back to the configured ceiling: the persisted narrowing is obsolete
            try {
                Files.deleteIfExists(directory.resolve("capacity"));
            } catch (final IOException e) {
                LOGGER.log(Level.FINE, e, () -> "cannot clear concurrency capacity file of " + directory);
            }
            return;
        }
        try {
            Files.createDirectories(directory);
            final var file = directory.resolve("capacity");
            Files.writeString(
                    file,
                    Integer.toString(value),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (final IOException e) {
            LOGGER.log(Level.FINE, e, () -> "cannot persist concurrency capacity of " + directory);
        }
    }

    /** Degrades to an in-memory budget after a filesystem failure, once and for good. */
    private void degrade(final IOException error) {
        synchronized (waiters) {
            if (fallback == null) {
                LOGGER.log(
                        Level.WARNING,
                        error,
                        () -> "file leases unavailable under " + directory
                                + ", falling back to a per-process concurrency budget");
                fallback = new InMemoryGate(capacity);
            }
            final var degraded = fallback;
            // each queued waiter is handed exactly one pending grant on the fallback gate (the
            // poller sees an empty queue from now on, so nothing double-acquires): a waiter their
            // caller abandoned meanwhile returns the granted slot instead of leaking it
            for (final var waiter : waiters) {
                degraded.acquire().whenComplete((release, grantError) -> {
                    if (grantError != null) {
                        waiter.granted.completeExceptionally(grantError);
                    } else if (!waiter.granted.complete(release)) {
                        release.run();
                    }
                });
            }
            waiters.clear();
        }
    }

    /** One queued caller; {@link #leaseNow()} returns its lease when a slot is free. */
    private final class Waiter {
        private final CompletableFuture<Runnable> granted = new CompletableFuture<>();

        /** Leases a slot right now, or returns {@code null} when every slot is taken. */
        private Runnable leaseNow() {
            final var degraded = fallback;
            if (degraded != null) {
                final var granted = degraded.acquire().toCompletableFuture();
                return granted.isDone() ? granted.join() : null;
            }
            if (inFlight >= capacity) {
                return null;
            }
            final var lease = lease();
            if (lease == null) {
                return null;
            }
            inFlight++;
            final var released = new AtomicBoolean();
            final Runnable release = () -> {
                if (released.compareAndSet(false, true)) {
                    lease.run();
                    synchronized (waiters) {
                        if (inFlight > 0) {
                            inFlight--;
                        }
                        outstanding.remove(this);
                    }
                }
            };
            outstanding.add(release);
            return release;
        }
    }

    /**
     * Tries every slot in turn and returns the release action of the first free one.
     *
     * @return the release action, or {@code null} when all the slots are leased.
     */
    private Runnable lease() {
        final List<Path> slots;
        try {
            Files.createDirectories(directory);
            slots = ensureSlots();
        } catch (final IOException e) {
            degrade(e);
            return null; // the waiter is served by the degraded gate from now on
        }
        for (final var slot : slots) {
            final var guard = JVM_GUARDS.computeIfAbsent(slot, path -> new Semaphore(1));
            if (!guard.tryAcquire()) {
                continue; // another gate of this JVM already leases it: FileLock would overlap
            }
            FileChannel channel = null;
            try {
                channel = FileChannel.open(slot, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                final FileLock lock = channel.tryLock();
                if (lock == null) {
                    guard.release();
                    channel.close();
                    continue; // leased by a peer process
                }
                final var channelRef = channel;
                final var state = new LeaseState(channelRef, lock, guard);
                CLEANER.register(state, state::clean);
                return state::run;
            } catch (final OverlappingFileLockException e) {
                guard.release();
                closeQuietly(channel);
            } catch (final IOException | RuntimeException e) {
                guard.release();
                closeQuietly(channel);
                if (e instanceof IOException io) {
                    degrade(io);
                }
            }
        }
        return null;
    }

    /**
     * Lists the slot files, creating the missing ones. The list is sorted so the scan order is stable
     * and fair across processes; slots are never deleted (see the class javadoc).
     */
    private List<Path> ensureSlots() throws IOException {
        final var slots = new ArrayList<Path>(capacity);
        for (var i = 0; i < capacity; i++) {
            final var slot = directory.resolve("slot-" + i + ".lock");
            if (!Files.exists(slot)) {
                Files.write(slot, new byte[0], StandardOpenOption.CREATE);
            }
            slots.add(slot);
        }
        return slots;
    }

    private static void closeQuietly(final FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (final IOException e) {
                LOGGER.log(Level.FINEST, e, () -> "cannot close a concurrency slot channel");
            }
        }
    }

    /** Strongly held lease resources, released by the gate or by the cleaner when it is leaked. */
    private static final class LeaseState implements Runnable {
        private final FileChannel channel;
        private final FileLock lock;
        private final Semaphore guard;
        private final AtomicBoolean released = new AtomicBoolean();

        private LeaseState(final FileChannel channel, final FileLock lock, final Semaphore guard) {
            this.channel = channel;
            this.lock = lock;
            this.guard = guard;
        }

        @Override
        public void run() {
            if (released.compareAndSet(false, true)) {
                try {
                    if (lock.isValid()) {
                        lock.release();
                    }
                } catch (final IOException e) {
                    LOGGER.log(Level.FINEST, e, () -> "cannot release a concurrency lease");
                } finally {
                    try {
                        channel.close();
                    } catch (final IOException e) {
                        LOGGER.log(Level.FINEST, e, () -> "cannot close a concurrency slot channel");
                    } finally {
                        // closing the channel may drop EVERY record lock of the file in this process,
                        // which is why the JVM guard is held for the whole lease
                        guard.release();
                    }
                }
            }
        }

        /** Cleaner action: same as {@link #run()} but tolerant to being called on a foreign thread. */
        private void clean() {
            run();
        }
    }
}
