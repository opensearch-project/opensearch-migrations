package org.opensearch.migrations.tracing.commoncontexts;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import org.opensearch.migrations.tracing.IInstrumentConstructor;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

public interface IConnectionContext<S extends IInstrumentConstructor> extends IScopedInstrumentationAttributes<S> {
    static final AttributeKey<String> CONNECTION_ID_ATTR = AttributeKey.stringKey("connectionId");
    static final AttributeKey<String> NODE_ID_ATTR = AttributeKey.stringKey("nodeId");
    String CHANNEL_SCOPE = "Channel";

    String getConnectionId();
    String getNodeId();

    @Override
    default IInstrumentationAttributes<S> getEnclosingScope() { return null; }

    @Override
    default AttributesBuilder fillAttributes(AttributesBuilder builder) {
        return builder.put(CONNECTION_ID_ATTR, getConnectionId())
                .put(NODE_ID_ATTR, getNodeId());
    }
    default String getScopeName() { return CHANNEL_SCOPE; }
}
