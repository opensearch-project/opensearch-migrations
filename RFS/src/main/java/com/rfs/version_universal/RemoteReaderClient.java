package com.rfs.version_universal;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.rfs.common.OpenSearchClient;
import com.rfs.common.http.ConnectionContext;
import com.rfs.common.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
public class RemoteReaderClient extends OpenSearchClient {

    public RemoteReaderClient(ConnectionContext connection) {
        super(connection);
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
                .retryWhen(checkIfItemExistsRetryStrategy)
            )
            .collectMap(Entry::getKey, Entry::getValue)
            .block();
    
        var globalMetadata = globalMetadataFromParts(responses);
        log.atDebug()
            .setMessage("Combined global metadata:\n{}")
            .addArgument(globalMetadata::toString)
            .log();
        return globalMetadata;
    }
    
    private ObjectNode globalMetadataFromParts(Map<String, ObjectNode> templatesDetails) {
        var rootNode = objectMapper.createObjectNode();
    
        templatesDetails.forEach((name, json) -> {
            if (json != null && json.size() != 0) {
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
                .retryWhen(checkIfItemExistsRetryStrategy)
            )
            .collectList()
            .block();

        var indexData = combineIndexDetails(indexDetailsList);
        log.atDebug()
            .setMessage("Index data combined:\n{}")
            .addArgument(indexData::toString)
            .log();
        return indexData;
    }

    ObjectNode combineIndexDetails(List<ObjectNode> indexDetailsResponse) {
        var combinedDetails = objectMapper.createObjectNode();
        indexDetailsResponse.stream().forEach(detailsResponse -> {
            detailsResponse.fields().forEachRemaining(indexDetails -> {
                var indexName = indexDetails.getKey();
                combinedDetails.putIfAbsent(indexName, objectMapper.createObjectNode());
                var existingIndexDetails = (ObjectNode)combinedDetails.get(indexName);
                indexDetails.getValue().fields().forEachRemaining(details -> {
                    existingIndexDetails.set(details.getKey(), details.getValue());
                });
            });
        });
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
            log.error("Unable to get json response: ", e);
            return Mono.error(new OperationFailed("Unable to get json response: " + e.getMessage(), resp));
        }
    }

    Mono<ObjectNode> getJsonForTemplateApis(HttpResponse resp) {
        if (resp.statusCode != 200) {
            return Mono.error(new OperationFailed("Unexpected status code " + resp.statusCode, resp));
        }
        try {
            var tree = (ObjectNode) objectMapper.readTree(resp.body);
            if (tree.size() == 1) {
                var dearrayed = objectMapper.createObjectNode();
                // This is OK because there is only a single item in this collection
                var fieldName = tree.fieldNames().next();
                var arrayOfItems = tree.get(fieldName);
                for (var child : arrayOfItems) {
                    var node = (ObjectNode)child;
                    if (node.size() == 2) {
                        var fields = node.fieldNames();
                        var f1 = fields.next();
                        var f2 = fields.next();
                        var itemName = node.get(f1).isTextual() ? node.get(f1).asText() : node.get(f2).asText();
                        var detailsNode = !node.get(f1).isTextual() ? node.get(f1) : node.get(f2);
                        dearrayed.set(itemName, detailsNode);
                    }
                }
                return Mono.just(dearrayed);
            }
            return Mono.just(tree);
        } catch (Exception e) {
            log.error("Unable to get json response: ", e);
            return Mono.error(new OperationFailed("Unable to get json response: " + e.getMessage(), resp));
        }
    }
}
