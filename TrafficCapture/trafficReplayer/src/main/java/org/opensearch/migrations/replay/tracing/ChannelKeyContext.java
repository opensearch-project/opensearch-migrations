package org.opensearch.migrations.replay.tracing;

import lombok.Getter;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.tracing.AbstractNestedSpanContext;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;
import org.opensearch.migrations.tracing.IWithStartTime;

public class ChannelKeyContext extends AbstractNestedSpanContext<IInstrumentationAttributes>
        implements IChannelKeyContext, IWithStartTime {
    @Getter
    final ISourceTrafficChannelKey channelKey;

    public ChannelKeyContext(IInstrumentationAttributes enclosingScope, ISourceTrafficChannelKey channelKey) {
        super(enclosingScope);
        this.channelKey = channelKey;
        setCurrentSpan("Connection", "channel");
    }

    @Override
    public String toString() {
        return channelKey.toString();
    }

}
