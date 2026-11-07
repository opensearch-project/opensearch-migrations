package org.opensearch.migrations.reindexer.faileddocumentstream.source;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationConverter;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.common.bulk.operations.IndexOperationMeta;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Goes through the sink's real convert-then-transform path rather than hand-shaping the input. */
class FailedDocumentStreamRecordTransformerTest {

    private static final ObjectMapper MAPPER = ObjectMapperFactory.createDefaultMapper();
    private static final String ORDERS = "orders-2024";

    private final FailedDocumentStreamRecordTransformer transformer = new FailedDocumentStreamRecordTransformer();

    /** What the sink hands a transformer for one record. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asSinkWouldPresent(String recordLine, String collection) {
        var document = new Document(null, recordLine.getBytes(StandardCharsets.UTF_8),
            Document.Operation.UPSERT, Map.of(), Map.of());
        var op = BulkOperationConverter.fromDocument(document, collection);
        return List.of(MAPPER.convertValue(op, Map.class));
    }

    /** {@code _index} is on the concrete metadata, not the shared interface. */
    private static String indexOf(BulkOperationSpec op) {
        return ((IndexOperationMeta) op.getOperation()).getIndex();
    }

    private BulkOperationSpec transformOne(String recordLine, String collection) {
        var transformed = (List<?>) transformer.transformJson(asSinkWouldPresent(recordLine, collection));
        assertThat(transformed, hasSize(1));
        return MAPPER.convertValue(transformed.get(0), BulkOperationSpec.class);
    }

    @Test
    void turnsARecordBackIntoTheOperationItRecords() {
        var line = FailedDocumentStreamFixtures.record(ORDERS, "doc-1", FailureClass.NON_RETRYABLE);

        var op = transformOne(line, ORDERS);

        assertThat(op, instanceOf(IndexOp.class));
        assertThat(op.getOperation().getId(), equalTo("doc-1"));
        assertThat(indexOf(op), equalTo(ORDERS));
        // The stored source-index document, not the whole record.
        assertThat(op.getDocument().get("field"), equalTo("value-for-doc-1"));
        assertThat(op.getDocument().containsKey("requestItem"), is(false));
    }

    @Test
    void addressesTheIndexThePipelineIsWriting() {
        // Must not disagree with the bulk endpoint the sink posts to.
        var line = FailedDocumentStreamFixtures.record("some-other-index", "doc-1", FailureClass.NON_RETRYABLE);

        var op = transformOne(line, ORDERS);

        assertThat(indexOf(op), equalTo(ORDERS));
    }

    @Test
    void leavesTheRecordItWasGivenAlone() {
        // A later transform may still read the record as emitted.
        var line = FailedDocumentStreamFixtures.record(ORDERS, "doc-1", FailureClass.NON_RETRYABLE);
        var presented = asSinkWouldPresent(line, ORDERS);
        @SuppressWarnings("unchecked")
        var record = (Map<String, Object>) presented.get(0).get("document");

        transformer.transformJson(presented);

        @SuppressWarnings("unchecked")
        var requestItem = (Map<String, Object>) record.get("requestItem");
        @SuppressWarnings("unchecked")
        var operation = (Map<String, Object>) requestItem.get("operation");
        assertThat(operation.get("_index"), equalTo(ORDERS));
        assertThat(record.containsKey("responseItem"), is(true));
    }

    @Test
    void carriesAnOperationWithNoIdThroughUntouched() {
        // Sending it is the sink's decision; this does not synthesize identity.
        var line = FailedDocumentStreamFixtures.record(ORDERS, null, FailureClass.NON_RETRYABLE);

        var op = transformOne(line, ORDERS);

        assertThat(op.getOperation().getId(), is(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void saysSoWhenChainedOntoSomethingThatIsNotAFailureStream() {
        var notARecord = List.of(Map.<String, Object>of(
            "document", Map.of("field", "value"),
            "operation", Map.of("_index", ORDERS)));

        var thrown = assertThrows(IllegalArgumentException.class,
            () -> transformer.transformJson(notARecord));

        assertThat(thrown.getMessage(), containsString("requestItem"));
    }

    @Test
    void rejectsInputThatIsNotAListOfOperations() {
        assertThrows(IllegalArgumentException.class, () -> transformer.transformJson(Map.of("a", "b")));
        assertThrows(IllegalArgumentException.class, () -> transformer.transformJson(null));
    }
}
