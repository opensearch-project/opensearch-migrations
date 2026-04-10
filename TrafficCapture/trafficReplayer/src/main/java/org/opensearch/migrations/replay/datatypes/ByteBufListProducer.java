package org.opensearch.migrations.replay.datatypes;

import java.util.function.Supplier;

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
    public abstract int numByteBufs();

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
