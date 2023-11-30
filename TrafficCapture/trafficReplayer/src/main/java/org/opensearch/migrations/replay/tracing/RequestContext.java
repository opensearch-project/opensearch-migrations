package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.trace.Span;
import lombok.Getter;
import org.opensearch.migrations.coreutils.SpanGenerator;
import org.opensearch.migrations.coreutils.SpanWithParentGenerator;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.tracing.EmptyContext;
import org.opensearch.migrations.tracing.IConnectionContext;
import org.opensearch.migrations.tracing.IReplayerRequestContext;
import org.opensearch.migrations.tracing.IRequestContext;
import org.opensearch.migrations.tracing.IWithAttributes;
import org.opensearch.migrations.tracing.IWithStartTime;

import java.time.Instant;

public class RequestContext implements IReplayerRequestContext, IWithStartTime {
    @Getter
    IConnectionContext enclosingScope;
    @Getter
    final UniqueReplayerRequestKey replayerRequestKey;
    @Getter
    final Instant startTime;
    @Getter
    final Span currentSpan;

    IWithAttributes<IWithAttributes<EmptyContext>> foo;

    public RequestContext(ChannelKeyContext enclosingScope, UniqueReplayerRequestKey replayerRequestKey,
                          SpanWithParentGenerator spanGenerator) {
        this.enclosingScope = enclosingScope;
        this.replayerRequestKey = replayerRequestKey;
        this.startTime = Instant.now();
        this.currentSpan = spanGenerator.apply(getPopulatedAttributes(), enclosingScope.getCurrentSpan());
    }

    public ChannelKeyContext getChannelKeyContext() {
        return new ChannelKeyContext(replayerRequestKey.trafficStreamKey,
                innerAttributesToIgnore_LeavingOriginalAttributesInPlace->currentSpan);
    }

    public String getConnectionId() {
        return enclosingScope.getConnectionId();
    }

    public String getNodeId() {
        return enclosingScope.getNodeId();
    }

    @Override
    public long sourceRequestIndex() {
        return replayerRequestKey.getSourceRequestIndex();
    }

    @Override
    public long replayerRequestIndex() {
        return replayerRequestKey.getReplayerRequestIndex();
    }

    public ISourceTrafficChannelKey getChannelKey() {
        return replayerRequestKey.trafficStreamKey;
    }
}
