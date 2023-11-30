package org.opensearch.migrations.tracing.commoncontexts;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import org.opensearch.migrations.tracing.EmptyContext;
import org.opensearch.migrations.tracing.IWithAttributes;

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
