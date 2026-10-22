package org.opensearch.migrations.replay.tracing;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.opensearch.migrations.replay.identity.ConnectionProcessingId;

import lombok.Getter;
import lombok.NonNull;

/**
 * Reference-counted connection contexts keyed by the complete process-local connection lifetime.
 */
public class ChannelContextManager
    implements Function<ConnectionProcessingId, IReplayContexts.IConnectionContext> {
    @Getter
    private final IRootReplayerContext globalContext;

    public ChannelContextManager(@NonNull IRootReplayerContext globalContext) {
        this.globalContext = globalContext;
    }

    private static final class RefCountedContext {
        @Getter
        private final IReplayContexts.IConnectionContext context;
        private int refCount;

        private RefCountedContext(IReplayContexts.IConnectionContext context) {
            this.context = context;
            this.refCount = 1;
        }

        private void retain() {
            if (refCount == Integer.MAX_VALUE) {
                throw new IllegalStateException(
                    "connection context reference count overflow for "
                        + context.getConnectionProcessingId()
                );
            }
            refCount = Math.incrementExact(refCount);
        }

        private boolean isFinalReference() {
            return refCount == 1;
        }

        private void releaseRetainedReference() {
            if (refCount <= 1) {
                throw new IllegalStateException(
                    "invalid retained-reference release for "
                        + context.getConnectionProcessingId()
                );
            }
            refCount = Math.decrementExact(refCount);
        }
    }

    private final ConcurrentHashMap<ConnectionProcessingId, RefCountedContext>
        connectionToChannelContextMap = new ConcurrentHashMap<>();

    @Override
    public IReplayContexts.IConnectionContext apply(ConnectionProcessingId connectionProcessingId) {
        return retainOrCreateContext(connectionProcessingId);
    }

    public IReplayContexts.IConnectionContext retainOrCreateContext(
        @NonNull ConnectionProcessingId connectionProcessingId
    ) {
        return connectionToChannelContextMap.compute(
            connectionProcessingId,
            (ignored, existing) -> {
                if (existing == null) {
                    return new RefCountedContext(
                        globalContext.createConnectionContext(connectionProcessingId)
                    );
                }
                existing.retain();
                return existing;
            }
        ).context;
    }

    public IReplayContexts.IConnectionContext releaseContextFor(
        @NonNull IReplayContexts.IConnectionContext context
    ) {
        var connectionProcessingId = context.getConnectionProcessingId();
        connectionToChannelContextMap.compute(
            connectionProcessingId,
            (ignored, existing) -> {
                if (existing == null) {
                    throw new IllegalStateException(
                        "no retained connection context for " + connectionProcessingId
                    );
                }
                if (existing.context != context) {
                    throw new IllegalStateException(
                        "connection context identity mismatch for " + connectionProcessingId
                    );
                }
                if (existing.isFinalReference()) {
                    context.close();
                    return null;
                }
                existing.releaseRetainedReference();
                return existing;
            }
        );
        return context;
    }
}
