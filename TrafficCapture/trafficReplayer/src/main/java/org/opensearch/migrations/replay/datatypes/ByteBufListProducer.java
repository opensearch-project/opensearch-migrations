package org.opensearch.migrations.replay.datatypes;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.lifecycle.ResourceOwnership;

import io.netty.buffer.ByteBuf;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;
import lombok.NonNull;

/**
 * An owned supplier of {@link ByteBufList} that also advertises the number
 * of ByteBufs each produced list will contain. This count is used for pacing calculations
 * (inter-packet interval) without needing to materialize the list.
 * <p>
 * Production callers transfer one instance and close it after all retries are complete.
 * The reference-counted base remains temporarily for compatibility with transformation code.
 * <p>
 * Each call to {@link #get()} returns the same underlying ByteBufList for the
 * trivial (non-resigning) case. Implementations that regenerate content (e.g.,
 * re-signing auth headers) may return a fresh ByteBufList on each call.
 */
public abstract class ByteBufListProducer extends AbstractReferenceCounted
    implements Supplier<ByteBufList>, OwnedPreparedRequest {
    private final AtomicBoolean closed = new AtomicBoolean();
    private ResourceOwnership.Metrics ownershipMetrics = ResourceOwnership.Metrics.NOOP;
    private ResourceOwnership.Tracker ownership;

    @Override
    public abstract int numByteBufs();

    /**
     * Returns an attempt-scoped view. Producers that allocate a new list per attempt must override this
     * and return an owned payload so the caller can release it after response evaluation.
     */
    @Override
    public AttemptPayload newAttempt() {
        var copies = get().streamUnretained().toArray(ByteBuf[]::new);
        return AttemptPayload.owned(new ByteBufList(copies), ownershipMetrics);
    }

    @Override
    public DiagnosticPayload retainDiagnosticCopy() {
        var copies = get().streamUnretained().toArray(ByteBuf[]::new);
        return new DiagnosticPayload(new ByteBufList(copies), ownershipMetrics);
    }

    public final synchronized void trackOwnership(@NonNull ResourceOwnership.Metrics metrics) {
        if (closed.get()) {
            metrics.invariantFailure(ResourceOwnership.Type.PREPARED_REQUEST);
            throw new IllegalStateException("cannot track a closed prepared request");
        }
        if (ownership != null) {
            ownership.invariantFailure();
            throw new IllegalStateException("prepared request ownership was already tracked");
        }
        ownershipMetrics = metrics;
        ownership = new ResourceOwnership.Tracker(
            metrics,
            ResourceOwnership.Type.PREPARED_REQUEST,
            ownedBufferCount(),
            ownedBytes()
        );
    }

    protected final ResourceOwnership.Metrics ownershipMetrics() {
        return ownershipMetrics;
    }

    protected int ownedBufferCount() {
        return 0;
    }

    protected long ownedBytes() {
        return 0;
    }

    @Override
    public final synchronized void close() {
        if (ownership != null) {
            ownership.close(this::releasePreparedRequest);
            return;
        }
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            releasePreparedRequest();
        } catch (Throwable t) {
            closed.set(false);
            throw t;
        }
    }

    private void releasePreparedRequest() {
        if (refCnt() != 1) {
            throw new IllegalStateException("prepared request has shared ownership; refCnt=" + refCnt());
        }
        release();
        closed.set(true);
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
            protected int ownedBufferCount() {
                return packets.size();
            }

            @Override
            protected long ownedBytes() {
                return packets.readableBytes();
            }

            @Override
            protected void deallocate() {
                packets.release();
            }
        };
    }
}
