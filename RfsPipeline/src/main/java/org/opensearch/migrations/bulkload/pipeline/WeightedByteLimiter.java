package org.opensearch.migrations.bulkload.pipeline;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

/**
 * A fair asynchronous weighted semaphore whose permits are measured in serialized bytes.
 *
 * <p>Callers may reserve a pessimistic maximum before materializing an object, then shrink
 * the permit to the exact serialized size. A permit must remain held for as long as the
 * charged object remains live.
 */
public final class WeightedByteLimiter {
    private final long capacity;
    private final ArrayDeque<Waiter> waiters = new ArrayDeque<>();
    private long available;
    private long maxUsed;

    public WeightedByteLimiter(long capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        this.capacity = capacity;
        this.available = capacity;
    }

    public long capacity() {
        return capacity;
    }

    public synchronized long usedBytes() {
        return capacity - available;
    }

    public synchronized long maxUsedBytes() {
        return maxUsed;
    }

    public Mono<Permit> acquire(long bytes) {
        if (bytes < 0) {
            return Mono.error(new IllegalArgumentException("permit bytes must be non-negative, got " + bytes));
        }
        if (bytes > capacity) {
            return Mono.error(new IllegalArgumentException(
                "permit request " + bytes + " exceeds limiter capacity " + capacity
            ));
        }
        if (bytes == 0) {
            return Mono.just(Permit.noop());
        }
        return Mono.create(sink -> {
            Waiter waiter = null;
            Permit permit = null;
            synchronized (this) {
                if (waiters.isEmpty() && available >= bytes) {
                    available -= bytes;
                    recordUsed();
                    permit = new Permit(this, bytes);
                } else {
                    waiter = new Waiter(bytes, sink);
                    waiters.addLast(waiter);
                }
            }
            if (permit != null) {
                sink.success(permit);
            } else {
                var queued = waiter;
                sink.onCancel(() -> cancel(queued));
            }
        });
    }

    private void cancel(Waiter waiter) {
        synchronized (this) {
            waiters.remove(waiter);
        }
    }

    private void resize(Permit permit, long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("permit bytes must be non-negative, got " + bytes);
        }
        long released;
        synchronized (this) {
            if (permit.released.get()) {
                throw new IllegalStateException("permit has already been released");
            }
            if (bytes > permit.bytes) {
                throw new IllegalArgumentException(
                    "a permit may only shrink: " + permit.bytes + " -> " + bytes
                );
            }
            released = permit.bytes - bytes;
            permit.bytes = bytes;
            available += released;
        }
        if (released > 0) {
            drain();
        }
    }

    private void release(Permit permit) {
        if (!permit.released.compareAndSet(false, true)) {
            return;
        }
        synchronized (this) {
            available += permit.bytes;
            permit.bytes = 0;
        }
        drain();
    }

    private void drain() {
        while (true) {
            Waiter waiter;
            Permit permit;
            synchronized (this) {
                waiter = waiters.peekFirst();
                if (waiter == null || waiter.bytes > available) {
                    return;
                }
                waiters.removeFirst();
                available -= waiter.bytes;
                recordUsed();
                permit = new Permit(this, waiter.bytes);
            }
            waiter.sink.success(permit);
        }
    }

    private void recordUsed() {
        maxUsed = Math.max(maxUsed, capacity - available);
    }

    private record Waiter(long bytes, MonoSink<Permit> sink) {}

    public static final class Permit implements AutoCloseable {
        private final WeightedByteLimiter owner;
        private final AtomicBoolean released = new AtomicBoolean();
        private long bytes;

        private Permit(WeightedByteLimiter owner, long bytes) {
            this.owner = owner;
            this.bytes = bytes;
        }

        private static Permit noop() {
            var permit = new Permit(null, 0);
            permit.released.set(false);
            return permit;
        }

        public long bytes() {
            return bytes;
        }

        public void shrinkTo(long bytes) {
            if (owner == null) {
                if (bytes != 0) {
                    throw new IllegalArgumentException("cannot resize a zero-byte permit to " + bytes);
                }
                return;
            }
            owner.resize(this, bytes);
        }

        public void release() {
            if (owner == null) {
                released.set(true);
                return;
            }
            owner.release(this);
        }

        @Override
        public void close() {
            release();
        }
    }
}
