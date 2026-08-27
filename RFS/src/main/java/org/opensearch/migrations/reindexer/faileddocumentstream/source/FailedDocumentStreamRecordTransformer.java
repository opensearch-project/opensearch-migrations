package org.opensearch.migrations.reindexer.faileddocumentstream.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.transform.IJsonTransformer;

import lombok.extern.slf4j.Slf4j;

/**
 * Turns a failure-stream record back into the bulk operation it records.
 *
 * <pre>
 *   NDJSON record --[this]--&gt; operation --[user's transform]--&gt; sink
 * </pre>
 *
 * <p>The record's {@code requestItem} is already a bulk operation, so this swaps it in and the
 * user's transform runs on the result. Since the stream stores the pre-transform document, a
 * transform fixed since the original run takes effect on redrive.
 *
 * <p>Mandatory for {@link FailedDocumentStreamSource}: without it the sink writes whole records as
 * documents under server-assigned ids.
 */
@Slf4j
public class FailedDocumentStreamRecordTransformer implements IJsonTransformer {

    static final String FIELD_DOCUMENT = "document";
    static final String FIELD_OPERATION = "operation";
    static final String FIELD_INDEX = "_index";

    @Override
    @SuppressWarnings("unchecked")
    public Object transformJson(Object incomingJson) {
        if (!(incomingJson instanceof List<?> items)) {
            throw new IllegalArgumentException("Expected a list of bulk operations, got "
                + (incomingJson == null ? "null" : incomingJson.getClass().getName()));
        }
        var converted = new ArrayList<Object>(items.size());
        for (var item : items) {
            converted.add(toOperation((Map<String, Object>) item));
        }
        return converted;
    }

    private Map<String, Object> toOperation(Map<String, Object> item) {
        var record = asMap(item.get(FIELD_DOCUMENT),
            "a failure-stream record under '" + FIELD_DOCUMENT + "'");
        var requestItem = asMap(record.get(FailedDocumentStreamSource.FIELD_REQUEST_ITEM),
            "a '" + FailedDocumentStreamSource.FIELD_REQUEST_ITEM + "' object");

        // Copy, not mutate: a later transform may still read the record as emitted.
        var operationSpec = new LinkedHashMap<>(requestItem);
        var operation = new LinkedHashMap<>(asMap(operationSpec.get(FIELD_OPERATION),
            "an '" + FIELD_OPERATION + "' object"));
        // Match the bulk endpoint the sink posts to.
        targetIndexOf(item).ifPresent(index -> operation.put(FIELD_INDEX, index));
        operationSpec.put(FIELD_OPERATION, operation);
        return operationSpec;
    }

    @SuppressWarnings("unchecked")
    private java.util.Optional<Object> targetIndexOf(Map<String, Object> item) {
        if (item.get(FIELD_OPERATION) instanceof Map<?, ?> operation) {
            return java.util.Optional.ofNullable(((Map<String, Object>) operation).get(FIELD_INDEX));
        }
        return java.util.Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value, String what) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected " + what
                + " while converting a failure-stream record, got "
                + (value == null ? "nothing" : value.getClass().getName())
                + ". Is this transform chained ahead of a source other than the failure stream?");
        }
        return (Map<String, Object>) map;
    }
}
