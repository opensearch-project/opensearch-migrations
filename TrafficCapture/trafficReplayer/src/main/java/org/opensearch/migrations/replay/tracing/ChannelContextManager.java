package org.opensearch.migrations.replay.tracing;

import lombok.Getter;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;

import java.util.HashMap;
import java.util.function.Function;

public class ChannelContextManager implements Function<ITrafficStreamKey, IReplayContexts.IChannelKeyContext> {
    private final IInstrumentationAttributes globalContext;

    public ChannelContextManager(IInstrumentationAttributes globalContext) {
        this.globalContext = globalContext;
    }

    private static class RefCountedContext {
        @Getter final ReplayContexts.ChannelKeyContext context;
        private int refCount;

        private RefCountedContext(ReplayContexts.ChannelKeyContext context) {
            this.context = context;
        }

        ReplayContexts.ChannelKeyContext retain() {
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

    HashMap<String, RefCountedContext> connectionToChannelContextMap = new HashMap<>();

    public ReplayContexts.ChannelKeyContext apply(ITrafficStreamKey tsk) {
        return retainOrCreateContext(tsk);
    }

    public ReplayContexts.ChannelKeyContext retainOrCreateContext(ITrafficStreamKey tsk) {
        return connectionToChannelContextMap.computeIfAbsent(tsk.getConnectionId(),
                k-> new RefCountedContext(new ReplayContexts.ChannelKeyContext(globalContext, tsk))).retain();
    }

    public ReplayContexts.ChannelKeyContext releaseContextFor(ReplayContexts.ChannelKeyContext ctx) {
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
