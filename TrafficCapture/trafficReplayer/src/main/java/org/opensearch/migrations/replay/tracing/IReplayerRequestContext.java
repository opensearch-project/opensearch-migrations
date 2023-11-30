package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import org.opensearch.migrations.tracing.commoncontexts.IRequestContext;

public interface IReplayerRequestContext extends IRequestContext {
    static final AttributeKey<Long> REPLAYER_REQUEST_INDEX_KEY = AttributeKey.longKey("replayerRequestIndex");

    long replayerRequestIndex();

    @Override
    default AttributesBuilder fillAttributes(AttributesBuilder builder) {
        return IRequestContext.super.fillAttributes(
                builder.put(REPLAYER_REQUEST_INDEX_KEY, replayerRequestIndex()));
    }
}
