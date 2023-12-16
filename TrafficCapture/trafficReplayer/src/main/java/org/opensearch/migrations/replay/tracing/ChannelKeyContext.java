package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.trace.Span;
import lombok.Getter;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.tracing.ISpanGenerator;
import org.opensearch.migrations.tracing.IWithStartTime;

import java.time.Instant;

public class ChannelKeyContext implements IChannelKeyContext, IWithStartTime {
    @Getter
    final ISourceTrafficChannelKey channelKey;
    @Getter
    final Span currentSpan;
    @Getter
    final Instant startTime;

    public ChannelKeyContext(ISourceTrafficChannelKey channelKey, ISpanGenerator spanGenerator) {
        this.channelKey = channelKey;
        this.currentSpan = spanGenerator.apply(getPopulatedAttributes());
        this.startTime = Instant.now();
    }

    @Override
    public String toString() {
        return channelKey.toString();
    }

}
