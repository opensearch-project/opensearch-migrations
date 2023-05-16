package org.opensearch.migrations.transform;

public interface JsonTransformer {
    Object transformJson(Object incomingJson);
}
