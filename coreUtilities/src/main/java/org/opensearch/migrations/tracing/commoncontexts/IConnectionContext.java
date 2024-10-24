package org.opensearch.migrations.tracing.commoncontexts;

import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;

public interface IConnectionContext extends IScopedInstrumentationAttributes {
    static final AttributeKey<String> CONNECTION_ID_ATTR = AttributeKey.stringKey("connectionId");
    static final AttributeKey<String> NODE_ID_ATTR = AttributeKey.stringKey("nodeId");

    String getConnectionId();

    String getNodeId();

    @Override
    default IScopedInstrumentationAttributes getEnclosingScope() {
        return null;
    }

    @Override
    default AttributesBuilder fillAttributesForSpansBelow(AttributesBuilder builder) {
        return IScopedInstrumentationAttributes.super.fillAttributesForSpansBelow(builder).put(
            CONNECTION_ID_ATTR,
            getConnectionId()
        ).put(NODE_ID_ATTR, getNodeId());
    }
}
