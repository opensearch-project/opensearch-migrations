package org.opensearch.migrations.replay.tracing;

import lombok.Getter;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.tracing.IInstrumentConstructor;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;
import org.opensearch.migrations.tracing.ISpanGenerator;
import org.opensearch.migrations.tracing.SimpleMeteringClosure;

import java.util.HashMap;
import java.util.function.Function;

public class ChannelContextManager implements Function<ITrafficStreamKey, IChannelKeyContext> {
    public static final String TELEMETRY_SCOPE_NAME = "Channel";
    public static final SimpleMeteringClosure METERING_CLOSURE = new SimpleMeteringClosure(TELEMETRY_SCOPE_NAME);
    private final IInstrumentationAttributes globalContext;

    public ChannelContextManager(IInstrumentationAttributes globalContext) {
        this.globalContext = globalContext;
    }

    private static class RefCountedContext {
        @Getter final ChannelKeyContext context;
        private int refCount;

        private RefCountedContext(ChannelKeyContext context) {
            this.context = context;
        }

        ChannelKeyContext retain() {
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

    public ChannelKeyContext apply(ITrafficStreamKey tsk) {
        return retainOrCreateContext(tsk);
    }

    public ChannelKeyContext retainOrCreateContext(ITrafficStreamKey tsk) {
        return connectionToChannelContextMap.computeIfAbsent(tsk.getConnectionId(),
                k-> new RefCountedContext(new ChannelKeyContext(globalContext, tsk))).retain();
    }

    public ChannelKeyContext releaseContextFor(ChannelKeyContext ctx) {
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
