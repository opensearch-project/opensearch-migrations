package org.opensearch.migrations.bulkload.common;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.SneakyThrows;

@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BulkDocSection {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Static constants for field names
    private static final String FIELD_INDEX = "_index";
    private static final String FIELD_TYPE = "_type";
    private static final String FIELD_ID = "_id";
    private static final String COMMAND_INDEX = "index";
    private static final String FIELD_SOURCE = "source";
    
    // Static constants for exception messages
    private static final String SERIALIZATION_ERROR_MESSAGE = "Failed to serialize BulkDocSection to JSON";
    private static final String DESERIALIZATION_ERROR_MESSAGE = "Failed to deserialize BulkDocSection from map";
    
    // Static constant for newline character
    private static final String NEWLINE = "\n";

    @EqualsAndHashCode.Include
    @Getter
    private final String docId;
    private final ObjectNode indexCommand;
    private final ObjectNode source;

    public BulkDocSection(String id, String indexName, String type, String docBody) {
        this.docId = id;
        this.indexCommand = createIndexCommand(id, indexName, type);
        this.source = parseSource(docBody);
    }

    @SneakyThrows
    private static ObjectNode createIndexCommand(final String docId, final String indexName, final String type) {
        ObjectNode indexNode = OBJECT_MAPPER.createObjectNode();
        ObjectNode metadataNode = OBJECT_MAPPER.createObjectNode();
        metadataNode.put(FIELD_INDEX, indexName);
        metadataNode.put(FIELD_TYPE, type);
        metadataNode.put(FIELD_ID, docId);
        indexNode.set(COMMAND_INDEX, metadataNode);
        return indexNode;
    }

    @SneakyThrows
    private static ObjectNode parseSource(final String doc) {
        return (ObjectNode) OBJECT_MAPPER.readTree(doc);
    }

    public static String convertToBulkRequestBody(Collection<BulkDocSection> bulkSections) {
        StringBuilder builder = new StringBuilder();
        for (var section : bulkSections) {
            builder.append(section.asStringBuilder()).append(NEWLINE);
        }
        return builder.toString();
    }

    public StringBuilder asStringBuilder() {
        StringBuilder builder = new StringBuilder();
        try {
            String indexCommand = asBulkIndex();
            String sourceJson = asBulkSource();
            builder.append(indexCommand).append(NEWLINE).append(sourceJson);
            return builder;
        } catch (JsonProcessingException e) {
            throw new RuntimeException(SERIALIZATION_ERROR_MESSAGE, e);
        }
    }

    public String asString() {
        return asStringBuilder().toString();
    }

    private String asString(ObjectNode node) throws JsonProcessingException {
        return OBJECT_MAPPER.writeValueAsString(node);
    }

    private String asBulkIndex() throws JsonProcessingException {
        return asString(this.indexCommand);
    }

    private String asBulkSource() throws JsonProcessingException {
        return asString(this.source);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap() {
        var indexMap = OBJECT_MAPPER.convertValue(this.indexCommand, HashMap.class);
        var sourceMap = OBJECT_MAPPER.convertValue(this.source, Map.class);
        var mergedMap = indexMap;
        mergedMap.put(FIELD_SOURCE, sourceMap);
        return mergedMap;
    }


    @SuppressWarnings("unchecked")
    public static BulkDocSection fromMap(Map<String, Object> map) {
        try {
            Map<String, Object> indexMap = (Map<String, Object>) map.get(COMMAND_INDEX);
            String docId = (String) indexMap.get(FIELD_ID);
            String indexName = (String) indexMap.get(FIELD_INDEX);
            String type = (String) indexMap.get(FIELD_TYPE);
            String source = OBJECT_MAPPER.writeValueAsString(map.get(FIELD_SOURCE));
            return new BulkDocSection(docId, indexName, type, source);
        } catch (Exception e) {
            throw new RuntimeException(DESERIALIZATION_ERROR_MESSAGE, e);
        }
    }
}
