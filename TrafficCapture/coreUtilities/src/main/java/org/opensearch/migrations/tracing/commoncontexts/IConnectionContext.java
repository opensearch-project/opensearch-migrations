package org.opensearch.migrations.tracing.commoncontexts;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public interface IConnectionContext extends IScopedInstrumentationAttributes {
    static final AttributeKey<String> CONNECTION_ID_ATTR = AttributeKey.stringKey("connectionId");
    static final AttributeKey<String> NODE_ID_ATTR = AttributeKey.stringKey("nodeId");

    String getConnectionId();
    String getNodeId();

    @Override
    default IInstrumentationAttributes getEnclosingScope() { return null; }

    @Override
    default AttributesBuilder fillAttributes(AttributesBuilder builder) {
        return IScopedInstrumentationAttributes.super.fillAttributes(builder)
                .put(CONNECTION_ID_ATTR, getConnectionId())
                .put(NODE_ID_ATTR, getNodeId());
    }
}
