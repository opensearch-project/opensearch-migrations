package org.opensearch.migrations.bulkload.pipeline.adapter;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.pipeline.ir.GlobalMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.sink.MetadataSink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Real {@link MetadataSink} adapter that writes metadata to an OpenSearch cluster
 * via the existing {@link OpenSearchClient}.
 */
@Slf4j
public class OpenSearchMetadataSink implements MetadataSink {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createDefaultMapper();

    private final OpenSearchClient client;

    public OpenSearchMetadataSink(OpenSearchClient client) {
        this.client = client;
    }

    @Override
    public Mono<Void> writeGlobalMetadata(GlobalMetadataSnapshot metadata) {
        return Mono.fromRunnable(() -> {
            createTemplates(metadata.templates(), "legacy");
            createTemplates(metadata.indexTemplates(), "index");
            createTemplates(metadata.componentTemplates(), "component");
        });
    }

    @Override
    public Mono<Void> createIndex(IndexMetadataSnapshot metadata) {
        return Mono.fromRunnable(() -> OpenSearchIndexCreator.createIndex(client, metadata, OBJECT_MAPPER))
            .then();
    }

    private void createTemplates(ObjectNode templates, String type) {
        if (templates == null) {
            return;
        }
        templates.fieldNames().forEachRemaining(name -> {
            ObjectNode body = (ObjectNode) templates.get(name);
            log.info("Creating {} template: {}", type, name);
            try {
                switch (type) {
                    case "legacy" -> client.createLegacyTemplate(name, body, null);
                    case "index" -> client.createIndexTemplate(name, body, null);
                    case "component" -> client.createComponentTemplate(name, body, null);
                    default -> log.warn("Unknown template type: {}", type);
                }
            } catch (Exception e) {
                log.warn("Failed to create {} template '{}': {}", type, name, e.getMessage());
            }
        });
    }
}
