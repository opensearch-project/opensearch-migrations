package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.RfsDocument;
import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationConverter;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.transform.TransformationLoader;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalVersioningTransformationTest {
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, 9007199254740993L, Long.MAX_VALUE})
    void rawAndConvertedWritesPreserveAvailableVersions(Long version) throws Exception {
        var document = new LuceneAdapter().fromLucene(luceneDocument(version, "{ \"title\" : \"unchanged\" }"));
        var raw = new String(BulkNdjson.toRawNdjsonBytes(List.of(document), "products", false, MAPPER),
            StandardCharsets.UTF_8);
        var converted = BulkNdjson.toBulkNdjson(List.of(BulkOperationConverter.fromDocument(document, "products")), MAPPER);
        for (String ndjson : List.of(raw, converted)) {
            var action = MAPPER.readTree(ndjson.split("\n")[0]).path("index");
            if (version == null) {
                assertFalse(action.has("version"));
                assertFalse(action.has("version_type"));
            } else {
                assertEquals(version.longValue(), action.path("version").longValue());
                assertTrue(action.path("version").isIntegralNumber());
                assertEquals("external", action.path("version_type").asText());
            }
            assertEquals("d1", action.path("_id").asText());
            assertEquals("tenant-1", action.path("routing").asText());
            assertFalse(action.has("source_metadata"));
        }
        assertEquals(new String(document.source(), StandardCharsets.UTF_8), raw.split("\n")[1]);
    }

    @Test
    void customVersionAndBundledPolicyComposeWithoutLosingLongPrecision() throws Exception {
        String config = """
            [
              {"JsonJSTransformerProvider": {"initializationScript":
                "context => documents => documents.map(document => {\
                  if (document.operation.version !== '9223372036854775807') {\
                    throw new Error('The snapshot version must reach JavaScript as an exact decimal string');\
                  }\
                  document.operation.version = '9007199254740993'; return document; })"
              }},
              {"JsonJSTransformerProvider": {
                "initializationResourcePath": "js/externalVersioning.js",
                "bindingsObject": {"versionType": "external_gte"}
              }}
            ]
            """;
        var original = RfsDocument.fromLuceneDocument(luceneDocument(Long.MAX_VALUE, "{\"ext_version\":42}"), "products");
        try (var transformer = new TransformationLoader().getTransformerFactoryLoader(config)) {
            var operation = RfsDocument.transform(transformer, List.of(original)).get(0).document;
            assertEquals(Long.toString(Long.MAX_VALUE), operation.getSourceMetadata().get(Document.SOURCE_META_VERSION));
            assertEquals(Map.of("ext_version", 42), operation.getDocument());
            var action = MAPPER.readTree(BulkNdjson.toBulkNdjson(List.of(operation), MAPPER).split("\n")[0]).path("index");
            assertEquals(9007199254740993L, action.path("version").longValue());
            assertTrue(action.path("version").isIntegralNumber());
            assertEquals("external_gte", action.path("version_type").asText());
        }
    }

    @Test
    void unsafeApplicationNumberCannotFallBackToTheSnapshotVersion() throws Exception {
        String config = """
            [{"JsonJSTransformerProvider": {
              "initializationResourcePath": "js/externalVersioning.js",
              "bindingsObject": {"versionField": "ext_version"}
            }}]
            """;
        var document = RfsDocument.fromLuceneDocument(luceneDocument(7L, "{\"ext_version\":9007199254740993}"), "products");
        try (var transformer = new TransformationLoader().getTransformerFactoryLoader(config)) {
            assertThrows(RuntimeException.class, () -> RfsDocument.transform(transformer, List.of(document)));
        }
    }

    private static LuceneDocumentChange luceneDocument(Long version, String source) {
        return new LuceneDocumentChange(0, "d1", null, source.getBytes(StandardCharsets.UTF_8),
            "tenant-1", DocumentChangeType.INDEX, version);
    }
}
