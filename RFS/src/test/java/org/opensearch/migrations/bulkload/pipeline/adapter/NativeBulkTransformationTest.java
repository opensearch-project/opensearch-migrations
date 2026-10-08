package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationConverter;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.common.bulk.metadata.VersionControlMetadata;
import org.opensearch.migrations.bulkload.common.bulk.operations.DeleteOperationMeta;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.transformers.BulkOperationTransformer;
import org.opensearch.migrations.bulkload.transformers.BulkVersioningTransformerProvider;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.JsonCompositeTransformer;
import org.opensearch.migrations.transform.TransformationLoader;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NativeBulkTransformationTest {
    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();
    private static final String SOURCE = "{ \"title\" : \"source\" }";

    @ParameterizedTest
    @ValueSource(strings = {"internal", "external", "external_gte"})
    void configuredPoliciesKeepSourceBytesUnparsed(String policy) throws Exception {
        var document = document(SOURCE);
        try (var transformer = configured(policy)) {
            var operation = send(transformer, document);
            assertSame(document.source(), operation.getRawDocument());
            assertSame(document.source(), operation.getOriginalSourceBytes());
            String[] lines = BulkNdjson.toBulkNdjson(List.of(operation), MAPPER).split("\n");
            assertEquals(SOURCE, lines[1], "Metadata-only transformations must retain the exact source bytes");
            assertSame(document.source(), operation.getRawDocument(), "Serialization must not materialize the body");
            var action = MAPPER.readTree(lines[0]).path("index");
            assertEquals("d1", action.path("_id").asText());
            assertEquals("tenant", action.path("routing").asText());
            if (policy.equals("internal")) {
                assertFalse(action.has("version"));
                assertFalse(action.has("version_type"));
            } else {
                assertTrue(action.path("version").isIntegralNumber());
                assertEquals(Long.MAX_VALUE, action.path("version").longValue());
                assertEquals(policy, action.path("version_type").asText());
            }
        }
    }

    @Test
    void nativeChainRestoresSnapshotVersionWithoutParsingSource() throws Exception {
        String config = """
            [
              {"BulkVersioningTransformerProvider":{"versionType":"internal"}},
              {"BulkVersioningTransformerProvider":{"versionType":"external_gte"}}
            ]
            """;
        var document = document(SOURCE);
        try (var transformer = new TransformationLoader().getTransformerFactoryLoader(config)) {
            var operation = (IndexOp) send(transformer, document);
            assertSame(document.source(), operation.getRawDocument());
            assertEquals(Long.MAX_VALUE, operation.getOperation().getVersioning().getVersion());
            assertEquals("external_gte", operation.getOperation().getVersioning().getVersionType().getValue());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mixedJavaAndJavascriptChainsPreserveOrderAndOriginalSource(boolean nativeFirst) throws Exception {
        String nativeStage = "{\"BulkVersioningTransformerProvider\":{\"versionType\":\"external_gte\"}}";
        String javascriptStage = """
            {"JsonJSTransformerProvider":{"initializationScript":
              "context => documents => documents.map(doc => { \
                doc.document.seen = doc.operation.version_type; \
                doc.operation.version = '9007199254740993'; return doc; })"}}
            """;
        String config = "[" + (nativeFirst ? nativeStage + "," + javascriptStage : javascriptStage + "," + nativeStage) + "]";
        try (var transformer = new TransformationLoader().getTransformerFactoryLoader(config)) {
            var operation = (IndexOp) send(transformer, document(SOURCE));
            assertNull(operation.getRawDocument(), "Mixed chains retain the existing JSON transformation path");
            assertEquals(nativeFirst ? "external_gte" : "external", operation.getDocument().get("seen"));
            assertEquals(9007199254740993L, operation.getOperation().getVersioning().getVersion());
            assertEquals(Map.of("title", "source"), operation.getOriginalSource());
        }
    }

    @Test
    void nativeBodyEditMaterializesOnlyTheChangedBodyAndRetainsOriginal() throws Exception {
        var transformer = new BulkOperationTransformer() {
            @Override
            public List<BulkOperationSpec> transformOperations(List<BulkOperationSpec> operations) {
                operations.get(0).getDocument().put("title", "changed");
                return operations;
            }
        };
        var operation = send(transformer, document(SOURCE));
        assertNull(operation.getRawDocument());
        var lines = BulkNdjson.toBulkNdjson(List.of(operation), MAPPER).split("\n");
        assertEquals("changed", MAPPER.readTree(lines[1]).path("title").asText());
        assertEquals(Map.of("title", "source"), operation.getOriginalSource());
        // Failure reporting uses Jackson's ordinary operation serializer.
        assertEquals("changed", MAPPER.valueToTree(operation).path("document").path("title").asText());
        assertFalse(MAPPER.valueToTree(operation).has("raw_document"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void compositeOverridesAreNotBypassedByNativeDetection() {
        var nativeStage = new BulkVersioningTransformerProvider().createTransformer(Map.of());
        var transformer = new JsonCompositeTransformer(nativeStage) {
            @Override
            public Object transformJson(Object input) {
                var result = (List<Map<String, Object>>) super.transformJson(input);
                ((Map<String, Object>) result.get(0).get("document")).put("wrapped", true);
                return result;
            }
        };
        var operation = send(transformer, document(SOURCE));
        assertEquals(true, operation.getDocument().get("wrapped"));
        assertNull(operation.getRawDocument());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "9007199254740993", "\"9223372036854775807\""})
    void nativeApplicationFieldRetainsFullLongPrecision(String value) throws Exception {
        var transformer = new BulkVersioningTransformerProvider().createTransformer(
            Map.of("versionType", "external_gte", "versionField", List.of("nested", "version")));
        var operation = (IndexOp) send(transformer, document("{\"nested\":{\"version\":" + value + "}}"));
        assertEquals(Long.parseLong(MAPPER.readTree(value).asText()), operation.getOperation().getVersioning().getVersion());
        assertEquals("external_gte", operation.getOperation().getVersioning().getVersionType().getValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "\"92233720368547758070\"", "1.5", "null"})
    void invalidApplicationVersionCannotFallBackToSnapshot(String value) {
        var transformer = (BulkOperationTransformer) new BulkVersioningTransformerProvider().createTransformer(
            Map.of("versionField", "version"));
        var operation = BulkOperationConverter.fromRawDocument(document("{\"version\":" + value + "}"), "products");
        assertThrows(IllegalArgumentException.class, () -> transformer.transformOperations(List.of(operation)));
    }

    @Test
    void deletesDoNotInheritSnapshotVersionAndInternalModePreservesConcurrencyChecks() throws Exception {
        var deletion = new Document("d1", null, Document.Operation.DELETE,
            Map.of(Document.HINT_VERSION, "7"), Map.of(Document.SOURCE_META_VERSION, "7"));
        var operation = send(configured("external_gte"), deletion);
        var metadata = (DeleteOperationMeta) operation.getOperation();
        assertNull(metadata.getVersioning());
        metadata.setVersioning(VersionControlMetadata.builder()
            .version(7L).ifSeqNo(5L).ifPrimaryTerm(3L).build());
        var transformer = (BulkOperationTransformer) new BulkVersioningTransformerProvider()
            .createTransformer(Map.of("versionType", "internal"));
        transformer.transformOperations(List.of(operation));
        assertNull(metadata.getVersioning().getVersion());
        assertNull(metadata.getVersioning().getVersionType());
        assertEquals(5L, metadata.getVersioning().getIfSeqNo());
        assertEquals(3L, metadata.getVersioning().getIfPrimaryTerm());
    }

    private static IJsonTransformer configured(String policy) {
        return new TransformationLoader().getTransformerFactoryLoader(
            "[{\"BulkVersioningTransformerProvider\":{\"versionType\":\"" + policy + "\"}}]");
    }

    private static Document document(String source) {
        return new LuceneAdapter().fromLucene(new LuceneDocumentChange(0, "d1", null,
            source.getBytes(StandardCharsets.UTF_8), "tenant", DocumentChangeType.INDEX, Long.MAX_VALUE));
    }

    @SuppressWarnings("unchecked")
    private static BulkOperationSpec send(IJsonTransformer transformer, Document document) {
        var client = mock(OpenSearchClient.class);
        when(client.sendBulkRequest(anyString(), anyList(), any(), anyBoolean(), any()))
            .thenReturn(Mono.just(new OpenSearchClient.BulkResponse(200, "", null, "{}")));
        var sink = new OpenSearchDocumentSink(client, () -> transformer, false, DocumentExceptionAllowlist.empty(), null);
        sink.writeBatch("products", List.of(document)).block();
        ArgumentCaptor<List<BulkOperationSpec>> captured = ArgumentCaptor.forClass(List.class);
        verify(client).sendBulkRequest(anyString(), captured.capture(), any(), anyBoolean(), any());
        return captured.getValue().get(0);
    }
}
