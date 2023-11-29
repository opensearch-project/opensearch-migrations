package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;

public interface IConnectionContext extends IWithAttributes<EmptyContext> {
    static final AttributeKey<String> CONNECTION_ID_ATTR = AttributeKey.stringKey("connectionId");
    static final AttributeKey<String> NODE_ID_ATTR = AttributeKey.stringKey("nodeId");

    String getConnectionId();
    String getNodeId();

    @Override
    default EmptyContext getEnclosingScope() { return EmptyContext.singleton; }

    @Override
    default AttributesBuilder fillAttributes(AttributesBuilder builder) {
        return builder.put(CONNECTION_ID_ATTR, getConnectionId())
                .put(NODE_ID_ATTR, getNodeId());
    }
}
