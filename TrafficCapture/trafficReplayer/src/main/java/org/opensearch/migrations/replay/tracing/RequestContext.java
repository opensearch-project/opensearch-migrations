package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import lombok.NonNull;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;

public class RequestContext extends ConnectionContext {
    private static final ContextKey<UniqueReplayerRequestKey> UNIQUE_REQUEST_KEY = ContextKey.named("requestId");

    public RequestContext(UniqueReplayerRequestKey requestKey) {
        this(Context.current(), requestKey);
    }

    public RequestContext(ConnectionContext ctx, UniqueReplayerRequestKey requestKey) {
        this(ctx.context, requestKey);
    }

    public RequestContext(Context context, UniqueReplayerRequestKey requestKey) {
        super(context.with(UNIQUE_REQUEST_KEY, requestKey).with(CHANNEL_KEY_CONTEXT_KEY, requestKey.trafficStreamKey));
    }

    public @NonNull UniqueReplayerRequestKey getRequestKey() {
        return context.get(UNIQUE_REQUEST_KEY);
    }
}
