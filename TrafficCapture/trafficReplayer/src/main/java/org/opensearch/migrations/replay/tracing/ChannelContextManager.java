package org.opensearch.migrations.replay.tracing;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;

import lombok.Getter;

public class ChannelContextManager implements Function<ITrafficStreamKey, IReplayContexts.IChannelKeyContext> {
    @Getter
    private final RootReplayerContext globalContext;

    public ChannelContextManager(RootReplayerContext globalContext) {
        this.globalContext = globalContext;
    }

    private static class RefCountedContext {
        @Getter
        final IReplayContexts.IChannelKeyContext context;
        private int refCount;
        final int generation;

        RefCountedContext(IReplayContexts.IChannelKeyContext context, int generation) {
            this.context = context;
            this.generation = generation;
        }

        IReplayContexts.IChannelKeyContext retain() {
            refCount++;
            return context;
        }

        /**
         * Returns true if this was the final release
         *
         * @return
         */
        boolean release() {
            refCount--;
            assert refCount >= 0;
            return refCount == 0;
        }
    }

    ConcurrentHashMap<String, RefCountedContext> connectionToChannelContextMap = new ConcurrentHashMap<>();

    public IReplayContexts.IChannelKeyContext apply(ITrafficStreamKey tsk) {
        return retainOrCreateContext(tsk);
    }

    public IReplayContexts.IChannelKeyContext retainOrCreateContext(ITrafficStreamKey tsk) {
        var incomingGeneration = tsk.getSourceGeneration();
        return connectionToChannelContextMap.compute(
            tsk.getConnectionId(),
            (k, existing) -> {
                if (existing != null && existing.generation < incomingGeneration) {
                    // Stale context from a previous partition assignment — force-close it.
                    // Its ref count will never drain naturally since all pending commits for
                    // the old generation are dropped as IGNORED.
                    existing.context.close();
                    existing = null;
                }
                if (existing == null) {
                    existing = new RefCountedContext(globalContext.createChannelContext(tsk), incomingGeneration);
                }
                existing.retain();
                return existing;
            }
        ).context;
    }

    public IReplayContexts.IChannelKeyContext releaseContextFor(IReplayContexts.IChannelKeyContext ctx) {
        var connId = ctx.getConnectionId();
        var refCountedCtx = connectionToChannelContextMap.get(connId);
        assert ctx == refCountedCtx.context : "consistency mismatch";
        var finalRelease = refCountedCtx.release();
        if (finalRelease) {
            ctx.close();
            connectionToChannelContextMap.remove(connId);
        }
        return ctx;
    }
}
