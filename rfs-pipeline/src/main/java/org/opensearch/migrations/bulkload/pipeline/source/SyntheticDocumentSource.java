package org.opensearch.migrations.bulkload.pipeline.source;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.opensearch.migrations.bulkload.pipeline.ir.Document;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.Partition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import reactor.core.publisher.Flux;

/**
 * A synthetic {@link DocumentSource} for testing the writing side without any real snapshot.
 *
 * <p>Document IDs follow the pattern {@code {collectionName}-{partitionIndex}-{docNumber}}.
 * Document bodies are JSON: {@code {"field":"value-{docNumber}","partition":{partitionIndex}}}.
 */
public class SyntheticDocumentSource implements DocumentSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    public IndexMetadataSnapshot readCollectionMetadata(String collectionName) {
        ObjectNode mappings = MAPPER.createObjectNode();
        ObjectNode settings = MAPPER.createObjectNode();
        settings.put("number_of_shards", partitionCount);
        settings.put("number_of_replicas", 1);
        ObjectNode aliases = MAPPER.createObjectNode();
        return new IndexMetadataSnapshot(collectionName, partitionCount, 1, mappings, settings, aliases);
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
