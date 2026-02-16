package org.opensearch.migrations.bulkload.version_universal;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Slf4j
public class RemoteReaderClient {
    protected static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createDefaultMapper();
    private static final int DEFAULT_MAX_RETRY_ATTEMPTS = 3;
    private static final Duration DEFAULT_BACKOFF = Duration.ofSeconds(1);
    private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(10);
    protected static final Retry CHECK_IF_ITEM_EXISTS_RETRY_STRATEGY =
        Retry.backoff(DEFAULT_MAX_RETRY_ATTEMPTS, DEFAULT_BACKOFF)
            .maxBackoff(DEFAULT_MAX_BACKOFF);

    @Getter
    protected final RestClient client;

    public RemoteReaderClient(ConnectionContext connection) {
        this.client = new RestClient(connection);
    }

    protected Map<String, String> getTemplateEndpoints() {
        return Map.of(
            "index_template", "_index_template",
            "component_template", "_component_template",
            "templates", "_template"
        );
    }

    public ObjectNode getClusterData() {
        var responses = Flux.fromIterable(getTemplateEndpoints().entrySet())
            .flatMap(entry -> client
                .getAsync(entry.getValue(), null)
                .flatMap(this::getJsonForTemplateApis)
                .map(json -> Map.entry(entry.getKey(), json))
                .doOnError(e -> log.error("Error fetching template {}: {}", entry.getKey(), e.getMessage()))
                .retryWhen(CHECK_IF_ITEM_EXISTS_RETRY_STRATEGY)
            )
            .collectMap(Entry::getKey, Entry::getValue)
            .block();

        assert responses != null;
        var globalMetadata = globalMetadataFromParts(responses);
        log.atDebug().setMessage("Combined global metadata:\n{}").addArgument(globalMetadata).log();
        return globalMetadata;
    }
    
    private ObjectNode globalMetadataFromParts(@NonNull Map<String, ObjectNode> templatesDetails) {
        var rootNode = OBJECT_MAPPER.createObjectNode();
    
        templatesDetails.forEach((name, json) -> {
            if (json != null && !json.isEmpty()) {
                var inner = OBJECT_MAPPER.createObjectNode().set(name, json);
                rootNode.set(name, inner);
            }
        });
    
        return rootNode;
    }

    public ObjectNode getIndexes() {
        var indexDataEndpoints = List.of(
            "_all/_settings?format=json",
            "_all/_mappings?format=json",
            "_all/_alias?format=json"
        );

        var indexDetailsList = Flux.fromIterable(indexDataEndpoints)
            .flatMap(endpoint -> client
                .getAsync(endpoint, null)
                .flatMap(this::getJsonForIndexApis)
                .doOnError(e -> log.error(e.getMessage()))
                .retryWhen(CHECK_IF_ITEM_EXISTS_RETRY_STRATEGY)
            )
            .collectList()
            .block();

        var indexData = combineIndexDetails(indexDetailsList);
        log.atDebug().setMessage("Index data combined:\n{}").addArgument(indexData).log();
        return indexData;
    }

    ObjectNode combineIndexDetails(List<ObjectNode> indexDetailsResponse) {
        var combinedDetails = OBJECT_MAPPER.createObjectNode();
        indexDetailsResponse.stream().forEach(detailsResponse ->
            detailsResponse.properties().forEach(indexDetails -> {
                var indexName = indexDetails.getKey();
                combinedDetails.putIfAbsent(indexName, OBJECT_MAPPER.createObjectNode());
                var existingIndexDetails = (ObjectNode)combinedDetails.get(indexName);
                indexDetails.getValue().properties().forEach(details ->
                    existingIndexDetails.set(details.getKey(), details.getValue()));
            }));
        return combinedDetails;
    }

    Mono<ObjectNode> getJsonForIndexApis(HttpResponse resp) {
        if (resp.statusCode != 200) {
            return Mono.error(new OperationFailed("Unexpected status code " + resp.statusCode, resp));
        }
        try {
            var tree = (ObjectNode) OBJECT_MAPPER.readTree(resp.body);
            return Mono.just(tree);
        } catch (Exception e) {
            return logAndReturnJsonError(e, resp);
        }
    }

    Mono<ObjectNode> getJsonForTemplateApis(HttpResponse resp) {
        if (resp.statusCode != 200) {
            return Mono.error(new OperationFailed("Unexpected status code " + resp.statusCode, resp));
        }
    
        try {
            var tree = (ObjectNode) OBJECT_MAPPER.readTree(resp.body);

            if (tree.size() == 1 && tree.properties().iterator().next().getValue().isArray()) {
                return Mono.just(handleSingleItemArrayValueTree(tree));
            }
    
            return Mono.just(tree);
        } catch (Exception e) {
            return logAndReturnJsonError(e, resp);
        }
    }
    
    private ObjectNode handleSingleItemArrayValueTree(ObjectNode tree) {
        var dearrayed = OBJECT_MAPPER.createObjectNode();
        var fieldName = tree.fieldNames().next();
        var arrayOfItems = tree.get(fieldName);
    
        for (var child : arrayOfItems) {
            if (child.isObject()) {
                processChildNode((ObjectNode) child, dearrayed);
            } else {
                throw new IllegalArgumentException("Expected ObjectNode, got: " + child.getNodeType());
            }
        }
    
        return dearrayed;
    }
    
    private void processChildNode(ObjectNode node, ObjectNode dearrayed) {
        if (node.size() == 2) {
            var fields = node.fieldNames();
            var f1 = fields.next();
            var f2 = fields.next();
            var itemName = node.get(f1).isTextual() ? node.get(f1).asText() : node.get(f2).asText();
            var detailsNode = !node.get(f1).isTextual() ? node.get(f1) : node.get(f2);
    
            dearrayed.set(itemName, detailsNode);
        }
    }

    Mono<ObjectNode> logAndReturnJsonError(Exception e, HttpResponse resp) {
        String errorPrefix = "Unable to get json response: ";
        log.atError().setCause(e).setMessage(errorPrefix).log();
        return Mono.error(new OperationFailed(errorPrefix + e.getMessage(), resp));
    }

    public static class OperationFailed extends RuntimeException {
        public final HttpResponse response;

        public OperationFailed(String message, HttpResponse response) {
            super(message + "\nBody:\n" + response);
            this.response = response;
        }
    }
}
