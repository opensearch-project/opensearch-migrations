package org.opensearch.migrations.bulkload.pipeline.source;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.opensearch.migrations.bulkload.pipeline.ir.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.ir.Document;
import org.opensearch.migrations.bulkload.pipeline.ir.Partition;

import reactor.core.publisher.Flux;

/**
 * In-memory document source for testing. Generates synthetic documents with predictable
 * IDs and content, enabling deterministic pipeline tests without real snapshots.
 */
public class SyntheticDocumentSource implements DocumentSource {

    private final String collectionName;
    private final int partitionCount;
    private final int docsPerPartition;

    public SyntheticDocumentSource(String collectionName, int partitionCount, int docsPerPartition) {
        if (partitionCount < 1) {
            throw new IllegalArgumentException("partitionCount must be >= 1");
        }
        if (docsPerPartition < 0) {
            throw new IllegalArgumentException("docsPerPartition must be >= 0");
        }
        this.collectionName = collectionName;
        this.partitionCount = partitionCount;
        this.docsPerPartition = docsPerPartition;
    }

    @Override
    public List<String> listCollections() {
        return List.of(collectionName);
    }

    @Override
    public List<Partition> listPartitions(String collectionName) {
        return IntStream.range(0, partitionCount)
            .mapToObj(i -> new SyntheticPartition(collectionName, i))
            .map(Partition.class::cast)
            .toList();
    }

    @Override
    public CollectionMetadata readCollectionMetadata(String collectionName) {
        return new CollectionMetadata(collectionName, partitionCount, Map.of());
    }

    @Override
    public Flux<Document> readDocuments(Partition partition, long startingDocOffset) {
        var synth = (SyntheticPartition) partition;
        int count = docsPerPartition - (int) startingDocOffset;
        if (count <= 0) {
            return Flux.empty();
        }
        return Flux.range((int) startingDocOffset, count)
            .map(docNum -> {
                String id = synth.collectionName() + "-" + synth.index() + "-" + docNum;
                String body = "{\"field\":\"value-" + docNum + "\",\"partition\":" + synth.index() + "}";
                return new Document(
                    id,
                    body.getBytes(StandardCharsets.UTF_8),
                    Document.Operation.UPSERT,
                    Map.of(),
                    Map.of()
                );
            });
    }

    /** Simple partition for synthetic sources. */
    public record SyntheticPartition(String collectionName, int index) implements Partition {
        @Override
        public String name() {
            return "synthetic/" + collectionName + "/" + index;
        }

        @Override
        public String collectionName() {
            return collectionName;
        }
    }
}
