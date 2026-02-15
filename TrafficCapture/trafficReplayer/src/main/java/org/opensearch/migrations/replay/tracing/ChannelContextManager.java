package org.opensearch.migrations.replay.tracing;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.util.TrafficChannelKeyFormatter;

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

        RefCountedContext(IReplayContexts.IChannelKeyContext context) {
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
        var channelKey = TrafficChannelKeyFormatter.format(tsk.getNodeId(), tsk.getConnectionId());
        return connectionToChannelContextMap.computeIfAbsent(
            channelKey,
            k -> new RefCountedContext(globalContext.createChannelContext(tsk))
        ).retain();
    }

    public IReplayContexts.IChannelKeyContext releaseContextFor(IReplayContexts.IChannelKeyContext ctx) {
        var channelKey = TrafficChannelKeyFormatter.format(ctx.getNodeId(), ctx.getConnectionId());
        var refCountedCtx = connectionToChannelContextMap.get(channelKey);
        assert ctx == refCountedCtx.context : "consistency mismatch";
        var finalRelease = refCountedCtx.release();
        if (finalRelease) {
            ctx.close();
            connectionToChannelContextMap.remove(channelKey);
        }
        return ctx;
    }
}
