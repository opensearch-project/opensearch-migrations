package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.pipeline.model.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.Partition;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * {@link DocumentSource} that reads documents from a Solr instance via its HTTP API.
 *
 * <p>Uses cursor-based pagination ({@code cursorMark}) for efficient streaming.
 * Each Solr collection maps to one collection in the pipeline IR. For standalone Solr,
 * each core is treated as a single-partition collection.
 */
@Slf4j
public class SolrDocumentSource implements DocumentSource {

    private static final int DEFAULT_PAGE_SIZE = 500;

    private final SolrClient client;
    private final int pageSize;

    public SolrDocumentSource(SolrClient client) {
        this(client, DEFAULT_PAGE_SIZE);
    }

    public SolrDocumentSource(SolrClient client, int pageSize) {
        this.client = client;
        this.pageSize = pageSize;
    }

    @Override
    public List<String> listCollections() {
        try {
            return client.listCollections();
        } catch (IOException e) {
            throw new SolrReadException("Failed to list Solr collections", e);
        }
    }

    @Override
    public List<Partition> listPartitions(String collectionName) {
        // For SolrCloud, we could list shards. For simplicity and correctness,
        // treat each collection as a single partition — Solr's /select endpoint
        // already handles distributed queries across shards.
        return List.of(new SolrShardPartition(collectionName, "shard1"));
    }

    @Override
    public CollectionMetadata readCollectionMetadata(String collectionName) {
        try {
            var schema = client.getSchema(collectionName);
            var sourceConfig = buildSourceConfig(schema);
            return new CollectionMetadata(collectionName, 1, sourceConfig);
        } catch (IOException e) {
            throw new SolrReadException("Failed to read schema for " + collectionName, e);
        }
    }

    @Override
    public Flux<Document> readDocuments(Partition partition, long startingDocOffset) {
        var solrPartition = (SolrShardPartition) partition;
        var collection = solrPartition.collection();

        return Flux.<Document>create(sink -> {
            try {
                paginateDocuments(collection, startingDocOffset, sink);
                sink.complete();
            } catch (IOException e) {
                sink.error(new SolrReadException("Failed to query " + collection, e));
            }
        });
    }

    @SuppressWarnings("java:S1854") // Sonar false positive: initial cursorMark="*" is used in first query
    private void paginateDocuments(
        String collection, long startingDocOffset, reactor.core.publisher.FluxSink<Document> sink
    ) throws IOException {
        long emitted = 0;
        String cursorMark = "*";
        String previousCursorMark;
        do {
            previousCursorMark = cursorMark;
            var response = client.query(collection, cursorMark, pageSize);
            var docs = response.docs();
            if (!docs.isArray() || docs.isEmpty()) {
                return;
            }
            for (var doc : docs) {
                emitted++;
                if (emitted > startingDocOffset) {
                    sink.next(toDocument(doc));
                }
            }
            cursorMark = response.nextCursorMark();
        } while (!sink.isCancelled() && !cursorMark.equals(previousCursorMark));
    }

    private Document toDocument(JsonNode solrDoc) {
        var id = extractId(solrDoc);
        // Remove Solr internal fields before storing as source
        var cleaned = solrDoc.deepCopy();
        if (cleaned instanceof ObjectNode obj) {
            obj.remove("_version_");
        }
        byte[] source = cleaned.toString().getBytes(StandardCharsets.UTF_8);
        return new Document(id, source, Document.Operation.UPSERT, Map.of(), Map.of());
    }

    private String extractId(JsonNode doc) {
        var idNode = doc.get("id");
        if (idNode != null) {
            return idNode.asText();
        }
        // Fallback: generate a hash-based ID
        return String.valueOf(doc.hashCode());
    }

    /**
     * Build sourceConfig from Solr schema. Converts Solr field definitions to
     * an OpenSearch-compatible mapping structure.
     */
    private Map<String, Object> buildSourceConfig(JsonNode schema) {
        var schemaNode = schema.path("schema");
        var mappings = SolrSchemaConverter.convertToOpenSearchMappings(
            schemaNode.path("fields"),
            schemaNode.path("dynamicFields"),
            schemaNode.path("copyFields"),
            schemaNode.path("fieldTypes")
        );
        return Map.of(
            CollectionMetadata.ES_MAPPINGS, mappings,
            "solr.schema", schemaNode
        );
    }

    public static class SolrReadException extends RuntimeException {
        public SolrReadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
