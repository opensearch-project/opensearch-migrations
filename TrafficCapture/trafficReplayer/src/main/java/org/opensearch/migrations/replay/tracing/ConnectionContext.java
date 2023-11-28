package org.opensearch.migrations.replay.tracing;

import io.netty.util.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import lombok.NonNull;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;

import java.util.stream.Stream;

public class ConnectionContext implements WithAttributes {
    protected static final ContextKey<ISourceTrafficChannelKey> CHANNEL_KEY_CONTEXT_KEY = ContextKey.named("channelKey");
    protected static final AttributeKey<ISourceTrafficChannelKey> CHANNEL_ATTR = AttributeKey.newInstance("channelKey");

    public final Context context;

    public ConnectionContext(ISourceTrafficChannelKey tsk) {
        this(Context.current().with(CHANNEL_KEY_CONTEXT_KEY, tsk));
    }

    public ConnectionContext(Context c) {
        assert c.get(CHANNEL_KEY_CONTEXT_KEY) != null;
        context = c;
    }

    public @NonNull ISourceTrafficChannelKey getChannelKey() {
        return context.get(CHANNEL_KEY_CONTEXT_KEY);
    }

    @Override
    public Stream<AttributeKey> getAttributeKeys() {
        return Stream.of(CHANNEL_ATTR);
    }
}
