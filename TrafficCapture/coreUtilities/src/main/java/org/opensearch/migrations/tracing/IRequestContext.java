package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;

public interface IRequestContext extends IWithAttributes<IConnectionContext> {
    static final AttributeKey<Long> SOURCE_REQUEST_INDEX_KEY = AttributeKey.longKey("sourceRequestIndex");

    long sourceRequestIndex();

    @Override
    default AttributesBuilder fillAttributes(AttributesBuilder builder) {
        return builder.put(SOURCE_REQUEST_INDEX_KEY, sourceRequestIndex());
    }
}
