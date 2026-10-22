package org.opensearch.migrations.reindexer.faileddocumentstream.source;

import java.util.List;

import org.opensearch.migrations.bulkload.pipeline.model.Partition;

/**
 * One {@code worker=} prefix within one {@code index=} prefix.
 *
 * @param collectionName the target index the records were destined for
 * @param name           the worker id that wrote them
 * @param objectKeys     the objects to read, in manifest order
 */
public record FailedDocumentStreamPartition(
    String collectionName,
    String name,
    List<String> objectKeys
) implements Partition {

    public FailedDocumentStreamPartition {
        objectKeys = objectKeys == null ? List.of() : List.copyOf(objectKeys);
    }
}
