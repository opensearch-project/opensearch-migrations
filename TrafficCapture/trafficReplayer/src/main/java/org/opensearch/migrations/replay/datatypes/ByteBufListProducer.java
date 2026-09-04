package org.opensearch.migrations.replay.datatypes;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;

/**
 * A reference-counted supplier of {@link ByteBufList} that also advertises the number
 * of ByteBufs each produced list will contain. This count is used for pacing calculations
 * (inter-packet interval) without needing to materialize the list.
 * <p>
 * Callers must {@link #retain()} before use and {@link #release()} when done (including
 * after all retries are complete). When the reference count reaches zero, the underlying
 * resources (e.g., the wrapped ByteBufList) are released.
 * <p>
 * Each call to {@link #get()} returns the same underlying ByteBufList for the
 * trivial (non-resigning) case. Implementations that regenerate content (e.g.,
 * re-signing auth headers) may return a fresh ByteBufList on each call.
 */
public abstract class ByteBufListProducer extends AbstractReferenceCounted implements Supplier<ByteBufList> {
    public static final class AttemptPayload implements AutoCloseable {
        private final ByteBufList packets;
        private final Runnable release;
        private final AtomicBoolean closed = new AtomicBoolean();

        private AttemptPayload(ByteBufList packets, Runnable release) {
            this.packets = packets;
            this.release = release;
        }

        public static AttemptPayload borrowed(ByteBufList packets) {
            return new AttemptPayload(packets, () -> {});
        }

        public static AttemptPayload owned(ByteBufList packets) {
            return new AttemptPayload(packets, packets::release);
        }

        public ByteBufList packets() {
            return packets;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release.run();
            }
        }
    }

    public abstract int numByteBufs();

    /**
     * Returns an attempt-scoped view. Producers that allocate a new list per attempt must override this
     * and return an owned payload so the caller can release it after response evaluation.
     */
    public AttemptPayload newAttempt() {
        return AttemptPayload.borrowed(get());
    }

    /**
     * Returns an independently owned request snapshot for evidence and diagnostics.
     */
    public ByteBufList diagnosticSnapshot() {
        var copies = get().streamUnretained().toArray(ByteBuf[]::new);
        return new ByteBufList(copies);
    }

    @Override
    public ReferenceCounted touch(Object hint) {
        return this;
    }

    public static ByteBufListProducer of(ByteBufList packets) {
        var size = packets.size();
        return new ByteBufListProducer() {
            @Override
            public int numByteBufs() {
                return size;
            }

            @Override
            public ByteBufList get() {
                return packets;
            }

            @Override
            protected void deallocate() {
                packets.release();
            }
        };
    }
}
