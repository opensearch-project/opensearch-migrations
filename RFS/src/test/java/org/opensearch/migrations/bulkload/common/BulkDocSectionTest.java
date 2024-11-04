package org.opensearch.migrations.bulkload.common;


import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkDocSectionTest {

    @Test
    void testConvertToBulkRequestBody() {
        String id1 = "id1";
        String indexName1 = "index1";
        String type1 = "_doc";
        String docBody1 = "{\"field\":\"value1\"}";

        String id2 = "id2";
        String indexName2 = "index2";
        String type2 = "_doc";
        String docBody2 = "{\"field\":\"value2\"}";

        BulkDocSection section1 = new BulkDocSection(id1, indexName1, type1, docBody1);
        BulkDocSection section2 = new BulkDocSection(id2, indexName2, type2, docBody2);

        Collection<BulkDocSection> bulkSections = Arrays.asList(section1, section2);

        String bulkRequestBody = BulkDocSection.convertToBulkRequestBody(bulkSections);

        assertEquals("{\"index\":{\"_id\":\"id1\",\"_type\":\"_doc\",\"_index\":\"index1\"}}\n" +
                "{\"field\":\"value1\"}\n" +
                "{\"index\":{\"_id\":\"id2\",\"_type\":\"_doc\",\"_index\":\"index2\"}}\n" +
                "{\"field\":\"value2\"}\n", bulkRequestBody);
    }

    @Test
    void testFromMap() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("_id", "test-id");
        metadata.put("_index", "test-index");
        metadata.put("_type", "_doc");

        Map<String, Object> sourceDoc = new HashMap<>();
        sourceDoc.put("field", "value");

        Map<String, Object> indexMap = new HashMap<>();
        indexMap.put("index", metadata);
        indexMap.put("source", sourceDoc);

        BulkDocSection bulkDocSection = BulkDocSection.fromMap(indexMap);

        assertNotNull(bulkDocSection);
        assertEquals("test-id", bulkDocSection.getDocId());
        assertEquals(metadata, bulkDocSection.toMap().get("index"));
        assertEquals(sourceDoc, bulkDocSection.toMap().get("source"));
    }

    @Test
    void testGetSerializedLength() {
        String id = "test-id";
        String indexName = "test-index";
        String type = "_doc";
        String docBody = "{\"field\":\"value\"}";

        BulkDocSection bulkDocSection = new BulkDocSection(id, indexName, type, docBody);
        assertEquals(bulkDocSection.asBulkIndexString().length(), bulkDocSection.getSerializedLength());
    }

    @Test
    void testAsBulkIndexString() {
        String id = "test-id";
        String indexName = "test-index";
        String type = "_doc";
        String docBody = "{\"field\":\"value\"}";

        BulkDocSection bulkDocSection = new BulkDocSection(id, indexName, type, docBody);

        String asString = bulkDocSection.asBulkIndexString();

        assertEquals("{\"index\":{\"_id\":\"test-id\",\"_type\":\"_doc\",\"_index\":\"test-index\"}}\n" +
                "{\"field\":\"value\"}", asString);
    }

    @Test
    void testToMap() {
        String id = "test-id";
        String indexName = "test-index";
        String type = "_doc";
        String docBody = "{\"field\":\"value\"}";

        BulkDocSection bulkDocSection = new BulkDocSection(id, indexName, type, docBody);

        Map<String, Object> map = bulkDocSection.toMap();

        assertNotNull(map);
        assertEquals(Map.of("_index",indexName,
                "_type", type,
               "_id", id ), map.get("index"));
        assertEquals(Map.of("field","value"), map.get("source"));
    }

    @Test
    void testDeserializationException() {
        // Create a BulkDocSection with invalid data to cause deserialization failure
        Exception exception = assertThrows(BulkDocSection.DeserializationException.class, () -> {
            new BulkDocSection(null, null, null, "{\"field_value");
        });

        assertTrue(exception.getMessage().contains("Failed to parse source doc"));
    }

    @Test
    void testLargeSourceDoc() throws JsonProcessingException {
        var writer = new ObjectMapper();
        // Generate a 25MB source document
        int targetSize = 25 * 1024 * 1024;
        StringBuilder docBuilder = new StringBuilder(targetSize);
        String key = "field_";
        String value = "value_";

        int i = 0;
        while (docBuilder.length() < targetSize) {
            docBuilder.append("\"").append(key).append(i).append("\":\"").append(value).append(i).append("\",");
            i++;
        }

        // Remove the trailing comma and wrap in braces
        docBuilder.setLength(docBuilder.length() - 1);
        String docBody = "{" + docBuilder + "}";

        String id = "test-large-doc-id";
        String indexName = "test-large-index";
        String type = "_doc";

        BulkDocSection bulkDocSection = new BulkDocSection(id, indexName, type, docBody);

        // Test asString
        String asString = bulkDocSection.asBulkIndexString();
        assertNotNull(asString);
        assertTrue(asString.contains(id));
        assertTrue(asString.contains(indexName));
        assertTrue(asString.contains(type));
        assertTrue(asString.contains(docBody));
        assertEquals(docBody.length() + 81, asString.length()); // add length of index command

        // Test toMap
        Map<String, Object> map = bulkDocSection.toMap();
        assertNotNull(map);
        assertTrue(map.containsKey("index"));
        assertTrue(map.containsKey("source"));
        assertEquals(docBody, writer.writeValueAsString(map.get("source")));

        // Test fromMap
        BulkDocSection fromMapSection = BulkDocSection.fromMap(map);
        assertNotNull(fromMapSection);
        @SuppressWarnings("unchecked")
        Map<String, Object> indexCommand = (Map<String, Object>) fromMapSection.toMap().get("index");
        assertEquals(id, fromMapSection.getDocId());
        assertEquals(indexName, indexCommand.get("_index"));
        assertEquals(type, indexCommand.get("_type"));
        assertEquals(id, indexCommand.get("_id"));
        assertEquals(docBody, writer.writeValueAsString(fromMapSection.toMap().get("source")));
    }
}
