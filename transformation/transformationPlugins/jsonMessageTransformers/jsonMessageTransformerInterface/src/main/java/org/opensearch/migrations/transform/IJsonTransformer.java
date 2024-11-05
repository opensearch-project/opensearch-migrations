package org.opensearch.migrations.transform;

import java.util.Map;

/**
 * This is a simple interface to convert a JSON object (String, Map, or Array) into another
 * JSON object.  Any changes to datastructures, nesting, order, etc should be intentional.
 */
public interface IJsonTransformer {
    Map<String, Object> transformJson(Map<String, Object> incomingJson);
}
