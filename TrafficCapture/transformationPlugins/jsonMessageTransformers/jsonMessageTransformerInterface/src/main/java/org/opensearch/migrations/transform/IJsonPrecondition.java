package org.opensearch.migrations.transform;

import java.util.Map;


public interface IJsonPrecondition {
    boolean evaluatePrecondition(Map<String, Object> incomingJson);
}
