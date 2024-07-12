package org.opensearch.migrations.tracing.commoncontexts;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;

import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public interface IHttpTransactionContext extends IScopedInstrumentationAttributes {
    static final AttributeKey<Long> SOURCE_REQUEST_INDEX_KEY = AttributeKey.longKey("sourceRequestIndex");

    long getSourceRequestIndex();

    @Override
    default AttributesBuilder fillAttributesForSpansBelow(AttributesBuilder builder) {
        return IScopedInstrumentationAttributes.super.fillAttributesForSpansBelow(builder).put(
            SOURCE_REQUEST_INDEX_KEY,
            getSourceRequestIndex()
        );
    }
}
