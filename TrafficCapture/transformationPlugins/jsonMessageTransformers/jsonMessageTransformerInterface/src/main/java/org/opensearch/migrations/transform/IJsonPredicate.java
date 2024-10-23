package org.opensearch.migrations.transform;

import java.util.Map;


public interface IJsonPredicate {
    boolean evaluatePredicate(Map<String, Object> incomingJson);
}
