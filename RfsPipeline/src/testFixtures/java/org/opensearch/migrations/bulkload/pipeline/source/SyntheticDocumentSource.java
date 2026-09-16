package org.opensearch.migrations.bulkload.pipeline.source;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import org.opensearch.migrations.bulkload.pipeline.model.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.Partition;
import org.opensearch.migrations.bulkload.pipeline.model.PositionedDocument;

import reactor.core.publisher.Flux;

/**
 * In-memory document source for testing. Generates synthetic documents with predictable
 * IDs and content, enabling deterministic pipeline tests without real snapshots.
 *
 * <p>Its cursor is the decimal index of the next document to emit.
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
    public Optional<Partition> findPartition(String collectionName, String partitionName) {
        return listPartitions(collectionName).stream()
            .filter(p -> p.name().equals(partitionName))
            .findFirst();
    }

    @Override
    public CollectionMetadata readCollectionMetadata(String collectionName) {
        return new CollectionMetadata(collectionName, partitionCount, Map.of());
    }

    @Override
    public Flux<PositionedDocument> readDocuments(Partition partition, String startingCursor) {
        var synth = (SyntheticPartition) partition;
        int start = startingCursor == null ? 0 : Integer.parseInt(startingCursor);
        int count = docsPerPartition - start;
        if (count <= 0) {
            return Flux.empty();
        }
        return Flux.range(start, count)
            .map(docNum -> {
                String id = synth.collectionName() + "-" + synth.index() + "-" + docNum;
                String body = "{\"field\":\"value-" + docNum + "\",\"partition\":" + synth.index() + "}";
                var doc = new Document(
                    id,
                    body.getBytes(StandardCharsets.UTF_8),
                    Document.Operation.UPSERT,
                    Map.of(),
                    Map.of()
                );
                return new PositionedDocument(doc, String.valueOf(docNum + 1));
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
