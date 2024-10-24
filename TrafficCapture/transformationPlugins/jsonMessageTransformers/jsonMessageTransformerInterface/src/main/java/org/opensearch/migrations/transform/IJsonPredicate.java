package org.opensearch.migrations.transform;

import java.util.Map;
import java.util.function.Predicate;

public interface IJsonPredicate extends Predicate<Map<String, Object>> {
}
