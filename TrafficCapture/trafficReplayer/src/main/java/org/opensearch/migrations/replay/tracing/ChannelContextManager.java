package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.tracing.ISpanGenerator;
import org.opensearch.migrations.tracing.SimpleMeteringClosure;
import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;

import java.util.HashMap;
import java.util.function.Function;

public class ChannelContextManager implements Function<ITrafficStreamKey, IChannelKeyContext> {
    public static final String TELEMETRY_SCOPE_NAME = "Channel";
    public static final SimpleMeteringClosure METERING_CLOSURE = new SimpleMeteringClosure(TELEMETRY_SCOPE_NAME);
    HashMap<String, ChannelKeyContext> connectionToChannelContextMap = new HashMap<>();

    public ChannelKeyContext apply(ITrafficStreamKey tsk) {
        return retainOrCreateContext(tsk);
    }

    public ChannelKeyContext retainOrCreateContext(ITrafficStreamKey tsk) {
        return retainOrCreateContext(tsk, METERING_CLOSURE.makeSpanContinuation("channel", null));
    }

    public ChannelKeyContext retainOrCreateContext(ITrafficStreamKey tsk, ISpanGenerator spanGenerator) {
        return connectionToChannelContextMap.computeIfAbsent(tsk.getConnectionId(),
                k-> new ChannelKeyContext(tsk, spanGenerator).retain());
    }

    public ChannelKeyContext releaseContextFor(ITrafficStreamKey tsk) {
        var connectionId = tsk.getConnectionId();
        var ctx = connectionToChannelContextMap.get(connectionId);
        var finalRelease = ctx.release();
        if (finalRelease) {
            ctx.currentSpan.end();
            connectionToChannelContextMap.remove(connectionId);
        }
        return ctx;
    }
}
