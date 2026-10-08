package org.opensearch.migrations.transform;

import java.util.List;
import java.util.Optional;

/**
 * This is a simple interface to convert a JSON object (String, Map, or Array) into another
 * JSON object.  Any changes to datastructures, nesting, order, etc should be intentional.
 */
public interface IJsonTransformer extends AutoCloseable {
    Object transformJson(Object incomingJson);

    /**
     * Return ordered stages that implement a typed execution contract, without JSON conversion.
     * An empty Optional means callers must use {@link #transformJson}; a present empty list is
     * an identity transformation. Wrappers may expose stages only when they preserve all behavior.
     */
    default <T> Optional<List<T>> getNativeStages(Class<T> nativeType) {
        return nativeType.isInstance(this)
            ? Optional.of(List.of(nativeType.cast(this)))
            : Optional.empty();
    }

    @Override
    default void close() throws Exception {}
}
