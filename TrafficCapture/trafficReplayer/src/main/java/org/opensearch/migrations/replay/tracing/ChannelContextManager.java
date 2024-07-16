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

        private RefCountedContext(IReplayContexts.IChannelKeyContext context) {
            this.context = context;
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
        return connectionToChannelContextMap.computeIfAbsent(
            tsk.getConnectionId(),
            k -> new RefCountedContext(globalContext.createChannelContext(tsk))
        ).retain();
    }

    public IReplayContexts.IChannelKeyContext releaseContextFor(IReplayContexts.IChannelKeyContext ctx) {
        var connId = ctx.getConnectionId();
        var refCountedCtx = connectionToChannelContextMap.get(connId);
        assert ctx == refCountedCtx.context;
        var finalRelease = refCountedCtx.release();
        if (finalRelease) {
            ctx.close();
            connectionToChannelContextMap.remove(connId);
        }
        return ctx;
    }
}
