package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.trace.Span;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.tracing.IReplayerRequestContext;
import org.opensearch.migrations.tracing.IWithStartTime;

import java.time.Instant;

public class RequestContext implements IReplayerRequestContext, IWithStartTime {
    @Getter
    final UniqueReplayerRequestKey replayerRequestKey;
    @Getter
    final Instant startTime;
    @Getter
    final Span currentSpan;

    public RequestContext(UniqueReplayerRequestKey replayerRequestKey, Span currentSpan) {
        this.replayerRequestKey = replayerRequestKey;
        this.currentSpan = currentSpan;
        this.startTime = Instant.now();
    }

    public ChannelKeyContext getChannelKeyContext() {
        return new ChannelKeyContext(replayerRequestKey.trafficStreamKey, currentSpan);
    }

    @Override
    public String getConnectionId() {
        return replayerRequestKey.trafficStreamKey.getConnectionId();
    }

    @Override
    public String getNodeId() {
        return replayerRequestKey.trafficStreamKey.getNodeId();
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
