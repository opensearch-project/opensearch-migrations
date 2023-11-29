package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.trace.Span;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.tracing.IConnectionContext;

@AllArgsConstructor
public class ChannelKeyContext implements IConnectionContext {
    @Getter
    final ISourceTrafficChannelKey channelKey;
    @Getter
    final Span currentSpan;

    @Override
    public String getConnectionId() {
        return channelKey.getConnectionId();
    }

    @Override
    public String getNodeId() {
        return channelKey.getNodeId();
    }
}
