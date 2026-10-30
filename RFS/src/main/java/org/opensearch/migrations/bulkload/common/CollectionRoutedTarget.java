package org.opensearch.migrations.bulkload.common;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Per-source-index request settings for an OpenSearch Serverless per-account endpoint: which
 * collection receives each index and whether that collection needs server-generated document IDs.
 */
public class CollectionRoutedTarget {
    private final ServerlessCollectionRouting routing;
    private final Predicate<String> requiresServerGeneratedIds;
    private final Map<String, Boolean> serverGeneratedIdsByCollection = new ConcurrentHashMap<>();

    /**
     * @param requiresServerGeneratedIds decides per collection, called at most once for each collection
     */
    public CollectionRoutedTarget(ServerlessCollectionRouting routing, Predicate<String> requiresServerGeneratedIds) {
        this.routing = routing;
        this.requiresServerGeneratedIds = requiresServerGeneratedIds;
    }

    public String collectionFor(String sourceIndex) {
        return routing.resolveRequired(sourceIndex);
    }

    /**
     * Resolves the source index and settles its collection's ID handling ahead of writing. The
     * decision may block on a probe request, so call this off the reactive threads.
     */
    public void prepare(String sourceIndex) {
        allowServerGeneratedIds(collectionFor(sourceIndex));
    }

    public boolean allowServerGeneratedIds(String collection) {
        return serverGeneratedIdsByCollection.computeIfAbsent(collection, requiresServerGeneratedIds::test);
    }

    /** Fails before any work starts if a source index has no collection. */
    public void validateSourceIndices(List<String> sourceIndices) {
        routing.resolveAll(sourceIndices);
    }
}
