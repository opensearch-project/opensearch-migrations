package org.opensearch.migrations.bulkload.version_universal;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
public class RemoteReaderClient extends OpenSearchClient {

    public RemoteReaderClient(ConnectionContext connection) {
        super(connection, null);
    }

    @Override
    protected String getCreateIndexPath(String indexName) {
        return indexName;
    }

    @Override
    protected String getBulkRequestPath(String indexName) {
        return indexName + "/_bulk";
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
                .retryWhen(OpenSearchClient.CHECK_IF_ITEM_EXISTS_RETRY_STRATEGY)
            )
            .collectMap(Entry::getKey, Entry::getValue)
            .block();

        assert responses != null;
        var globalMetadata = globalMetadataFromParts(responses);
        log.atDebug().setMessage("Combined global metadata:\n{}").addArgument(globalMetadata).log();
        return globalMetadata;
    }
    
    private ObjectNode globalMetadataFromParts(@NonNull Map<String, ObjectNode> templatesDetails) {
        var rootNode = objectMapper.createObjectNode();
    
        templatesDetails.forEach((name, json) -> {
            if (json != null && !json.isEmpty()) {
                var inner = objectMapper.createObjectNode().set(name, json);
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
                .retryWhen(OpenSearchClient.CHECK_IF_ITEM_EXISTS_RETRY_STRATEGY)
            )
            .collectList()
            .block();

        var indexData = combineIndexDetails(indexDetailsList);
        log.atDebug().setMessage("Index data combined:\n{}").addArgument(indexData).log();
        return indexData;
    }

    ObjectNode combineIndexDetails(List<ObjectNode> indexDetailsResponse) {
        var combinedDetails = objectMapper.createObjectNode();
        indexDetailsResponse.stream().forEach(detailsResponse ->
            detailsResponse.fields().forEachRemaining(indexDetails -> {
                var indexName = indexDetails.getKey();
                combinedDetails.putIfAbsent(indexName, objectMapper.createObjectNode());
                var existingIndexDetails = (ObjectNode)combinedDetails.get(indexName);
                indexDetails.getValue().fields().forEachRemaining(details ->
                    existingIndexDetails.set(details.getKey(), details.getValue()));
            }));
        return combinedDetails;
    }

    Mono<ObjectNode> getJsonForIndexApis(HttpResponse resp) {
        if (resp.statusCode != 200) {
            return Mono.error(new OperationFailed("Unexpected status code " + resp.statusCode, resp));
        }
        try {
            var tree = (ObjectNode) objectMapper.readTree(resp.body);
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
            var tree = (ObjectNode) objectMapper.readTree(resp.body);

            if (tree.size() == 1 && tree.fields().next().getValue().isArray()) {
                return Mono.just(handleSingleItemArrayValueTree(tree));
            }
    
            return Mono.just(tree);
        } catch (Exception e) {
            return logAndReturnJsonError(e, resp);
        }
    }
    
    private ObjectNode handleSingleItemArrayValueTree(ObjectNode tree) {
        var dearrayed = objectMapper.createObjectNode();
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
}
