package org.opensearch.migrations.bulkload.common;


import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkDocSectionTest {
    static final Map<String, Object> METADATA_1 = Map.of(
            "_id", "test-id",
            "_index", "test-index",
            "_type", "_doc");

    static final Map<String, Object> METADATA_2 = Map.of(
            "_id", "test-id",
            "_index", "test-index",
            "_type", "_doc",
            "routing", "routing1");

    static final Map<String, Object> SOURCE_DOC_1 = Map.of("field", "value");

    static final BulkDocSection BULK_DOC_SECTION_1 = new BulkDocSection("test-id", "test-index", "_doc",
            "{\"field\":\"value\"}");

    static final BulkDocSection BULK_DOC_SECTION_2 = new BulkDocSection("test-id", "test-index", "_doc",
            "{\"field\":\"value\"}", "routing1");

    static final String BULK_DOC_SECTION_1_STRING = "{\"index\":{\"_id\":\"test-id\",\"_type\":\"_doc\",\"_index\":\"test-index\"}}\n"
            + "{\"field\":\"value\"}";

    static final String BULK_DOC_SECTION_2_STRING = "{\"index\":{\"_id\":\"test-id\",\"_type\":\"_doc\",\"_index\":\"test-index\",\"routing\":\"routing1\"}}\n"
            + "{\"field\":\"value\"}";

    static Stream<Arguments> provideFromMapArgs() {
        return Stream.of(
                Arguments.of(METADATA_1, SOURCE_DOC_1),
                Arguments.of(METADATA_2, SOURCE_DOC_1));
    }

    static Stream<BulkDocSection> provideSerializedLengthArgs() {
        return Stream.of(
                BULK_DOC_SECTION_1,
                BULK_DOC_SECTION_2);
    }

    static Stream<Arguments> provideBulkIndexStringArgs() {
        return Stream.of(
                Arguments.of(BULK_DOC_SECTION_1, BULK_DOC_SECTION_1_STRING),
                Arguments.of(BULK_DOC_SECTION_2, BULK_DOC_SECTION_2_STRING));
    }

    static Stream<Arguments> provideToMapArgs() {
        return Stream.of(
                Arguments.of(BULK_DOC_SECTION_1, METADATA_1, SOURCE_DOC_1),
                Arguments.of(BULK_DOC_SECTION_2, METADATA_2, SOURCE_DOC_1));
    }

    @Test
    void testConvertToBulkRequestBody() {
        BulkDocSection section1 = new BulkDocSection("id1", "index1", "_doc", "{\"field\":\"value1\"}");
        BulkDocSection section2 = new BulkDocSection("id2", "index2", "_doc", "{\"field\":\"value2\"}");
        BulkDocSection section3 = new BulkDocSection("id3", "index3", "_doc", "{\"field\":\"value3\"}", "routing1");

        Collection<BulkDocSection> bulkSections = Arrays.asList(section1, section2, section3);

        String bulkRequestBody = BulkDocSection.convertToBulkRequestBody(bulkSections);

        String expectedRequestBody = "{"
                + "\"index\":{\"_id\":\"id1\",\"_type\":\"_doc\",\"_index\":\"index1\"}}\n"
                + "{\"field\":\"value1\"}\n"
                + "{\"index\":{\"_id\":\"id2\",\"_type\":\"_doc\",\"_index\":\"index2\"}}\n"
                + "{\"field\":\"value2\"}\n"
                + "{\"index\":{\"_id\":\"id3\",\"_type\":\"_doc\",\"_index\":\"index3\",\"routing\":\"routing1\"}}\n"
                + "{\"field\":\"value3\"}\n";

        assertEquals(expectedRequestBody, bulkRequestBody);
    }

    @ParameterizedTest
    @MethodSource("provideFromMapArgs")
    void testFromMap(Map<String, Object> metadata, Map<String, Object> sourceDoc) {
        Map<String, Object> indexMap = new HashMap<>();
        indexMap.put("index", metadata);
        indexMap.put("source", sourceDoc);

        BulkDocSection bulkDocSection = BulkDocSection.fromMap(indexMap);

        assertNotNull(bulkDocSection);
        assertEquals("test-id", bulkDocSection.getDocId());
        assertEquals(metadata, bulkDocSection.toMap().get("index"));
        assertEquals(sourceDoc, bulkDocSection.toMap().get("source"));
    }


    @ParameterizedTest
    @MethodSource("provideSerializedLengthArgs")
    void testGetSerializedLength(BulkDocSection bulkDocSection) {
        assertEquals(bulkDocSection.asBulkIndexString().length(), bulkDocSection.getSerializedLength());
    }

    @ParameterizedTest
    @MethodSource("provideBulkIndexStringArgs")
    void testAsBulkIndexString(BulkDocSection bulkDocSection, String expected) {
        String asString = bulkDocSection.asBulkIndexString();

        assertEquals(expected, asString);
    }

    @ParameterizedTest
    @MethodSource("provideToMapArgs")
    void testToMap(BulkDocSection bulkDocSection, Map<String, Object> metaData, Map<String, Object> source) {
        Map<String, Object> map = bulkDocSection.toMap();

        assertNotNull(map);
        assertEquals(metaData, map.get("index"));
        assertEquals(source, map.get("source"));
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
    @Tag("isolatedTest") // Mark as isolated test to control memory usage during parallel execution
    void testLargeSourceDoc() throws JsonProcessingException {
        var writer = new ObjectMapper();
        int _10MBSize = 10 * 1024 * 1024;
        var docBody = createDocOfSize(_10MBSize);

        String id = "test-large-doc-id";
        String indexName = "test-large-index";
        String type = "_doc";

        var bulkDocSection = new BulkDocSection(id, indexName, type, docBody);

        // Test asString
        var asString = bulkDocSection.asBulkIndexString();
        assertNotNull(asString);
        assertTrue(asString.contains(id));
        assertTrue(asString.contains(indexName));
        assertTrue(asString.contains(type));
        assertTrue(asString.contains(docBody));
        assertEquals(docBody.length() + 81, asString.length()); // add length of index command

        // Test toMap
        var map = bulkDocSection.toMap();
        assertNotNull(map);
        assertTrue(map.containsKey("index"));
        assertTrue(map.containsKey("source"));
        assertEquals(docBody, writer.writeValueAsString(map.get("source")));

        // Test fromMap
        var fromMapSection = BulkDocSection.fromMap(map);
        assertNotNull(fromMapSection);
        var convertedToMap = fromMapSection.toMap();
        @SuppressWarnings("unchecked")
        var indexCommand = (Map<String, Object>) convertedToMap.get("index");
        assertEquals(id, fromMapSection.getDocId());
        assertEquals(indexName, indexCommand.get("_index"));
        assertEquals(type, indexCommand.get("_type"));
        assertEquals(id, indexCommand.get("_id"));
        assertEquals(docBody, writer.writeValueAsString(convertedToMap.get("source")));
    }

    private String createDocOfSize(int targetSize) {
        StringBuilder docBuilder = new StringBuilder(targetSize);
        String key = "field_";
        String value = "value_";

        int i = 0;
        docBuilder.append("{");
        while (docBuilder.length() < targetSize) {
            docBuilder.append("\"").append(key).append(i).append("\":\"").append(value).append(i).append("\"");
            i++;
            if (docBuilder.length() < targetSize) {
                docBuilder.append(",");
            }
        }
        docBuilder.append("}");
        return docBuilder.toString();
    }
}
