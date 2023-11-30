package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.trace.Span;
import lombok.Getter;
import org.opensearch.migrations.coreutils.SpanGenerator;
import org.opensearch.migrations.coreutils.SpanWithParentGenerator;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.tracing.IConnectionContext;

public class ChannelKeyContext implements IConnectionContext {
    @Getter
    final ISourceTrafficChannelKey channelKey;
    @Getter
    final Span currentSpan;

    public ChannelKeyContext(ISourceTrafficChannelKey channelKey, SpanGenerator spanGenerator) {
        this.channelKey = channelKey;
        this.currentSpan = spanGenerator.apply(getPopulatedAttributes());
    }

    @Override
    public String getConnectionId() {
        return channelKey.getConnectionId();
    }

    @Override
    public String getNodeId() {
        return channelKey.getNodeId();
    }
}
