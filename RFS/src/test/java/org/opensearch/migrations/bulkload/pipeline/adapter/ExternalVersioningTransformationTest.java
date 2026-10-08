package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.RfsDocument;
import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationConverter;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.TransformationLoader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalVersioningTransformationTest {
    private static final String SNAPSHOT_CONFIG = """
        [{"JsonJSTransformerProvider": {"initializationResourcePath": "js/externalVersioning.js"}}]
        """;
    private static final String INTERNAL_CONFIG = """
        [{"JsonJSTransformerProvider": {"initializationResourcePath": "js/internalVersioning.js"}}]
        """;
    private static final String CUSTOM_CONFIG = """
        [{"JsonJSTransformerProvider": {"initializationScript":
          "context => documents => documents.map(document => {\
            if (typeof document.operation.version !== 'string') {\
              throw new Error('Action versions must preserve long precision as strings');\
            }\
            document.operation.version = document.operation.version + '';\
            return document;\
          })"
        }}]
        """;
    private static final String CONFIG = """
        [{
          "JsonJSTransformerProvider": {
            "initializationResourcePath": "js/externalVersioning.js",
            "bindingsObject": {"versionField": ["metadata", "revision"]}
          }
        }]
        """;

    @Mock
    private OpenSearchClient client;

    @Captor
    private ArgumentCaptor<List<BulkOperationSpec>> sentOperations;

    @ParameterizedTest
    @ValueSource(strings = {"7", "0", "9007199254740991", "\"9007199254740993\"", "\"9223372036854775807\""})
    void sourceVersionReachesBulkActionThroughBundledJavascript(String sourceVersion) throws Exception {
        when(client.sendBulkRequest(anyString(), anyList(), any(), anyBoolean(), any()))
            .thenReturn(Mono.just(new OpenSearchClient.BulkResponse(200, "", null, "{}")));
        String source = "{\"metadata\":{\"revision\":" + sourceVersion + "},\"title\":\"original\"}";
        var luceneDoc = new LuceneDocumentChange(0, "d1", null,
            source.getBytes(StandardCharsets.UTF_8), "tenant-1", DocumentChangeType.INDEX);
        var document = new LuceneAdapter().fromLucene(luceneDoc);
        var deletion = new Document("d2", null, Document.Operation.DELETE, Map.of(), Map.of());

        try (var transformer = loadTransformer()) {
            var sink = new OpenSearchDocumentSink(client, () -> transformer, false,
                DocumentExceptionAllowlist.empty(), null);
            sink.writeBatch("products", List.of(document, deletion)).block();
        }

        verify(client).sendBulkRequest(anyString(), sentOperations.capture(), any(), anyBoolean(), any());
        var mapper = ObjectMapperFactory.createDefaultMapper();
        String[] lines = BulkNdjson.toBulkNdjson(sentOperations.getValue(), mapper).split("\n");
        assertEquals(3, lines.length, "One index action, its body, and a delete action");
        var action = mapper.readTree(lines[0]).path("index");
        assertEquals("external", action.path("version_type").asText());
        assertTrue(action.path("version").isIntegralNumber(), "The wire version must be a JSON integer");
        assertEquals(Long.parseLong(sourceVersion.replace("\"", "")), action.path("version").longValue());
        assertEquals("d1", action.path("_id").asText());
        assertEquals("products", action.path("_index").asText());
        assertEquals("tenant-1", action.path("routing").asText());
        assertEquals(mapper.readTree(source), mapper.readTree(lines[1]));
        var deleteAction = mapper.readTree(lines[2]).path("delete");
        assertEquals("d2", deleteAction.path("_id").asText());
        assertFalse(deleteAction.has("version"));
        assertFalse(deleteAction.has("version_type"));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 7, 9007199254740993L, Long.MAX_VALUE})
    void snapshotVersionReachesBulkActionWithoutRounding(long version) throws Exception {
        when(client.sendBulkRequest(anyString(), anyList(), any(), anyBoolean(), any()))
            .thenReturn(Mono.just(new OpenSearchClient.BulkResponse(200, "", null, "{}")));
        var luceneDoc = new LuceneDocumentChange(0, "d1", null,
            "{\"ext_version\":42}".getBytes(StandardCharsets.UTF_8), null, DocumentChangeType.INDEX, version);
        var document = new LuceneAdapter().fromLucene(luceneDoc);
        assertEquals(Long.toString(version), document.sourceMetadata().get(Document.SOURCE_META_VERSION));

        try (var transformer = loadTransformer(SNAPSHOT_CONFIG)) {
            var sink = new OpenSearchDocumentSink(client, () -> transformer, false,
                DocumentExceptionAllowlist.empty(), null);
            sink.writeBatch("products", List.of(document)).block();
        }

        verify(client).sendBulkRequest(anyString(), sentOperations.capture(), any(), anyBoolean(), any());
        var mapper = ObjectMapperFactory.createDefaultMapper();
        String[] lines = BulkNdjson.toBulkNdjson(sentOperations.getValue(), mapper).split("\n");
        var action = mapper.readTree(lines[0]).path("index");
        assertEquals("external", action.path("version_type").asText());
        assertTrue(action.path("version").isIntegralNumber());
        assertEquals(version, action.path("version").longValue());
        assertFalse(action.has("source_metadata"));
        assertEquals(mapper.readTree(luceneDoc.source), mapper.readTree(lines[1]));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 7, 9007199254740993L, Long.MAX_VALUE})
    void defaultRawAndConvertedActionsPreserveSnapshotVersions(long version) throws Exception {
        var document = new LuceneAdapter().fromLucene(new LuceneDocumentChange(0, "d1", null,
            "{ \"title\" : \"unchanged\" }".getBytes(StandardCharsets.UTF_8), null, DocumentChangeType.INDEX, version));
        var mapper = ObjectMapperFactory.createDefaultMapper();
        var raw = new String(BulkNdjson.toRawNdjsonBytes(List.of(document), "products", false, mapper),
            StandardCharsets.UTF_8);
        var converted = BulkNdjson.toBulkNdjson(List.of(BulkOperationConverter.fromDocument(document, "products")), mapper);

        for (String ndjson : List.of(raw, converted)) {
            var action = mapper.readTree(ndjson.split("\n")[0]).path("index");
            assertEquals(version, action.path("version").longValue());
            assertTrue(action.path("version").isIntegralNumber());
            assertEquals("external", action.path("version_type").asText());
            assertFalse(action.has("source_metadata"));
        }
        assertEquals(new String(document.source(), StandardCharsets.UTF_8), raw.split("\n")[1],
            "The default path must preserve the raw document bytes");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 7, 9007199254740993L, Long.MAX_VALUE})
    void customJavascriptKeepsDefaultVersionsWithoutRounding(long version) throws Exception {
        when(client.sendBulkRequest(anyString(), anyList(), any(), anyBoolean(), any()))
            .thenReturn(Mono.just(new OpenSearchClient.BulkResponse(200, "", null, "{}")));
        var document = new LuceneAdapter().fromLucene(new LuceneDocumentChange(0, "d1", null,
            "{}".getBytes(StandardCharsets.UTF_8), null, DocumentChangeType.INDEX, version));
        try (var transformer = loadTransformer(CUSTOM_CONFIG)) {
            var sink = new OpenSearchDocumentSink(client, () -> transformer, false,
                DocumentExceptionAllowlist.empty(), null);
            sink.writeBatch("products", List.of(document)).block();
        }
        verify(client).sendBulkRequest(anyString(), sentOperations.capture(), any(), anyBoolean(), any());
        var mapper = ObjectMapperFactory.createDefaultMapper();
        var action = mapper.readTree(BulkNdjson.toBulkNdjson(sentOperations.getValue(), mapper).split("\n")[0])
            .path("index");
        assertEquals(version, action.path("version").longValue());
        assertEquals("external", action.path("version_type").asText());
    }

    @Test
    void bundledInternalVersioningRemovesOnlyWriteVersions() throws Exception {
        when(client.sendBulkRequest(anyString(), anyList(), any(), anyBoolean(), any()))
            .thenReturn(Mono.just(new OpenSearchClient.BulkResponse(200, "", null, "{}")));
        var document = new LuceneAdapter().fromLucene(new LuceneDocumentChange(0, "d1", null,
            "{\"version\":\"application value\"}".getBytes(StandardCharsets.UTF_8),
            "tenant-1", DocumentChangeType.INDEX, Long.MAX_VALUE));
        try (var transformer = loadTransformer(INTERNAL_CONFIG)) {
            var sink = new OpenSearchDocumentSink(client, () -> transformer, false,
                DocumentExceptionAllowlist.empty(), null);
            sink.writeBatch("products", List.of(document)).block();
        }
        verify(client).sendBulkRequest(anyString(), sentOperations.capture(), any(), anyBoolean(), any());
        var mapper = ObjectMapperFactory.createDefaultMapper();
        var operation = sentOperations.getValue().get(0);
        assertEquals(Long.toString(Long.MAX_VALUE), operation.getSourceMetadata().get(Document.SOURCE_META_VERSION));
        var lines = BulkNdjson.toBulkNdjson(sentOperations.getValue(), mapper).split("\n");
        var action = mapper.readTree(lines[0]).path("index");
        assertEquals("d1", action.path("_id").asText());
        assertEquals("tenant-1", action.path("routing").asText());
        assertFalse(action.has("version"));
        assertFalse(action.has("version_type"));
        assertFalse(action.has("source_metadata"));
        assertEquals(mapper.readTree(document.source()), mapper.readTree(lines[1]));
    }

    @Test
    void missingVersionsEmptyIdsAndDeletesDoNotAcquireExternalVersioning() throws Exception {
        var adapter = new LuceneAdapter();
        var documents = List.of(
            adapter.fromLucene(new LuceneDocumentChange(0, "no-version", null,
                "{}".getBytes(StandardCharsets.UTF_8), null, DocumentChangeType.INDEX)),
            adapter.fromLucene(new LuceneDocumentChange(1, "", null,
                "{}".getBytes(StandardCharsets.UTF_8), null, DocumentChangeType.INDEX, 7L)),
            adapter.fromLucene(new LuceneDocumentChange(2, "deleted", null,
                null, null, DocumentChangeType.DELETE, 7L))
        );
        var mapper = ObjectMapperFactory.createDefaultMapper();
        for (var document : documents) {
            var raw = new String(BulkNdjson.toRawNdjsonBytes(List.of(document), "products", false, mapper),
                StandardCharsets.UTF_8);
            var converted = BulkNdjson.toBulkNdjson(List.of(BulkOperationConverter.fromDocument(document, "products")), mapper);
            for (String ndjson : List.of(raw, converted)) {
                var action = mapper.readTree(ndjson.split("\n")[0]).elements().next();
                assertFalse(action.has("version"));
                assertFalse(action.has("version_type"));
            }
        }
    }

    @Test
    void legacyRfsDocumentAlsoExposesTheSnapshotVersion() throws Exception {
        var luceneDoc = new LuceneDocumentChange(0, "d1", null, "{}".getBytes(StandardCharsets.UTF_8),
            null, DocumentChangeType.INDEX, Long.MAX_VALUE);
        var original = RfsDocument.fromLuceneDocument(luceneDoc, "products");
        var mapper = ObjectMapperFactory.createDefaultMapper();
        var originalAction = mapper.readTree(BulkNdjson.toBulkNdjson(List.of(original.document), mapper).split("\n")[0])
            .path("index");
        assertEquals(Long.MAX_VALUE, originalAction.path("version").longValue());
        assertEquals("external", originalAction.path("version_type").asText());
        try (var transformer = loadTransformer(CUSTOM_CONFIG)) {
            var transformed = RfsDocument.transform(transformer, List.of(RfsDocument.fromLuceneDocument(luceneDoc, "products")));
            var ndjson = BulkNdjson.toBulkNdjson(List.of(transformed.get(0).document), mapper);
            var action = mapper.readTree(ndjson.split("\n")[0]).path("index");
            assertEquals(Long.MAX_VALUE, action.path("version").longValue());
            assertEquals("external", action.path("version_type").asText());
        }
        try (var transformer = loadTransformer(INTERNAL_CONFIG)) {
            var transformed = RfsDocument.transform(transformer, List.of(original));
            var action = mapper.readTree(BulkNdjson.toBulkNdjson(List.of(transformed.get(0).document), mapper).split("\n")[0])
                .path("index");
            assertFalse(action.has("version"));
            assertFalse(action.has("version_type"));
        }
    }

    @Test
    void missingSourceVersionFailsBeforeSendingTheBatch() throws Exception {
        try (var transformer = loadTransformer()) {
            var sink = new OpenSearchDocumentSink(client, () -> transformer, false,
                DocumentExceptionAllowlist.empty(), null);
            var document = new Document("d1", "{}".getBytes(StandardCharsets.UTF_8),
                Document.Operation.UPSERT, Map.of(), Map.of());

            assertThrows(RuntimeException.class, () -> sink.writeBatch("products", List.of(document)).block());
        }
        verifyNoInteractions(client);
    }

    private static IJsonTransformer loadTransformer() throws Exception {
        return loadTransformer(CONFIG);
    }

    private static IJsonTransformer loadTransformer(String config) throws Exception {
        return new TransformationLoader().getTransformerFactoryFromServiceLoader(config).findFirst().orElseThrow();
    }
}
