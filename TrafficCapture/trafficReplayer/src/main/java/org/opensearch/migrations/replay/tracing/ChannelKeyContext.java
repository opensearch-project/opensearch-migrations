package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.trace.Span;
import lombok.Getter;
import org.opensearch.migrations.tracing.ISpanGenerator;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;

import java.util.StringJoiner;

public class ChannelKeyContext implements IConnectionContext {
    @Getter
    final ISourceTrafficChannelKey channelKey;
    @Getter
    final Span currentSpan;

    public ChannelKeyContext(ISourceTrafficChannelKey channelKey, ISpanGenerator spanGenerator) {
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

    @Override
    public String toString() {
        return channelKey.toString();
    }
}
