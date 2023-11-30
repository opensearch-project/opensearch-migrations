package org.opensearch.migrations.tracing.commoncontexts;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import org.opensearch.migrations.tracing.IWithAttributes;

public interface IRequestContext extends IWithAttributes<IConnectionContext> {
    static final AttributeKey<Long> SOURCE_REQUEST_INDEX_KEY = AttributeKey.longKey("sourceRequestIndex");

    long getSourceRequestIndex();

    @Override
    default AttributesBuilder fillAttributes(AttributesBuilder builder) {
        return builder.put(SOURCE_REQUEST_INDEX_KEY, getSourceRequestIndex());
    }
}
