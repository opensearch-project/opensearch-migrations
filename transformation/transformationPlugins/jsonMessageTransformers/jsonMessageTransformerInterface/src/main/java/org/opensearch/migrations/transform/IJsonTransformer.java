package org.opensearch.migrations.transform;

/**
 * This is a simple interface to convert a JSON object (String, Map, or Array) into another
 * JSON object.  Any changes to datastructures, nesting, order, etc should be intentional.
 */
public interface IJsonTransformer extends AutoCloseable {
    Object transformJson(Object incomingJson);

    @Override
    default void close() throws Exception {}
}
