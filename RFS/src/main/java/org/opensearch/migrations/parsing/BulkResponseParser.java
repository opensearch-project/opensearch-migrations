package org.opensearch.migrations.parsing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import org.opensearch.migrations.BulkDocErrorTypes;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;
import lombok.Value;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
public class BulkResponseParser {
    private static JsonFactory jsonFactory = new JsonFactory();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Scans a bulk response for all operations that were a success
     * 
     * {
        "items": [
            {
                "{OPERATION}": {
                    "_id": "tt1979320",
                    "result": "created",
                    ...
     * 
     * @param bulkResponse The response to scan for successful documents
     * @return The list of doc ids that had a successful action 
     * @throws IOException If any errors occurred while parsing the document
     */
    public static List<String> findSuccessDocs(String bulkResponse) throws IOException {
        return findSuccessDocs(bulkResponse, DocumentExceptionAllowlist.empty());
    }

    /**
     * Scans a bulk response for all operations that were a success, including operations
     * that failed with allowlisted exceptions.
     * 
     * @param bulkResponse The response to scan for successful documents
     * @param allowlist Configuration for exceptions to treat as success
     * @return The list of doc ids that had a successful action or allowlisted failure
     * @throws IOException If any errors occurred while parsing the document
     */
    public static List<String> findSuccessDocs(String bulkResponse, DocumentExceptionAllowlist allowlist) throws IOException {
        var successfulDocumentIds = new ArrayList<String>();
        try (var parser = jsonFactory.createParser(bulkResponse)) {
            // Move to the start of the JSON object
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Expected data to start with an Object");
            }


            try {
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    var fieldName = parser.currentName();

                    if ("items".equals(fieldName)) {
                        scanItems(parser, successfulDocumentIds, allowlist);
                    } else {
                        // Skip other fields at the root level
                        parser.skipChildren();
                    }
                }
            } catch (IOException ioe) {
                log.warn("Unable to finish parsing the entire bulk response body", ioe);
            }
        }
        return successfulDocumentIds;
    }

    /**
     * Returns a BitSet where bit i is set if item i FAILED (and not allowlisted).
     * Use with nextSetBit() to iterate only over failures.
     * If response can't be parsed, returns null to indicate all docs should be retried.
     */
    public static BitSet getFailedPositions(String bulkResponse, DocumentExceptionAllowlist allowlist) {
        var failedPositions = new BitSet();
        boolean foundItems = false;
        try (var parser = jsonFactory.createParser(bulkResponse)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null; // Can't parse - retry all
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if ("items".equals(parser.currentName())) {
                    scanItemPositions(parser, failedPositions, allowlist);
                    foundItems = true;
                } else {
                    parser.skipChildren();
                }
            }
        } catch (IOException e) {
            log.warn("Unable to parse bulk response", e);
            return null; // Can't parse - retry all
        }
        if (!foundItems) {
            return null; // No items field - retry all
        }
        return failedPositions;
    }

    private static void scanItemPositions(JsonParser parser, BitSet failedPositions, DocumentExceptionAllowlist allowlist) throws IOException {
        if (parser.nextToken() != JsonToken.START_ARRAY) {
            throw new IOException("Expected 'items' to be an array");
        }
        int position = 0;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.getCurrentToken() == JsonToken.START_OBJECT) {
                if (isFailedItem(parser, allowlist)) {
                    failedPositions.set(position);
                }
                position++;
            }
        }
    }

    private static boolean isFailedItem(JsonParser parser, DocumentExceptionAllowlist allowlist) throws IOException {
        parser.nextToken(); // Move to action field name (e.g., "index")
        boolean failed;
        if (parser.nextToken() == JsonToken.START_OBJECT) {
            var docInfo = extractDocInfo(parser);
            failed = docInfo.getResult() == null && !isAllowedFailure(docInfo, allowlist);
        } else {
            // Unexpected structure - treat as failure
            failed = true;
            parser.skipChildren();
        }
        if (parser.nextToken() != JsonToken.END_OBJECT) {
            throw new IOException("Expected END_OBJECT after action object");
        }
        return failed;
    }

    private static void scanItems(JsonParser parser, List<String> successfulDocumentIds, DocumentExceptionAllowlist allowlist) throws IOException {
        // Move to the start of the items array
        if (parser.nextToken() != JsonToken.START_ARRAY) {
            throw new IOException("Expected 'items' to be an array");
        }

        // Iterate over each item in the array
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.getCurrentToken() == JsonToken.START_OBJECT) {
                // Each item is an object with one key (e.g., "index", "create", "update")
                parser.nextToken(); // Move to the action field

                if (parser.nextToken() == JsonToken.START_OBJECT) {
                    // Iterate over fields within the action object
                    var docInfo = extractDocInfo(parser);

                    // Check if the document was successfully created
                    if (docInfo.getResult() != null && docInfo.getId() != null) {
                        log.debug(
                            "Found successfully item, result '"
                                + docInfo.getResult()
                                + "'', id: '"
                                + docInfo.getId()
                                + "'"
                        );
                        successfulDocumentIds.add(docInfo.getId());
                    } else if (isAllowedFailure(docInfo, allowlist)) {
                        log.debug(
                            "Found item with allowlisted exception, errorType '"
                                + docInfo.getErrorType()
                                + "', id: '"
                                + docInfo.getId()
                                + "'"
                        );
                        successfulDocumentIds.add(docInfo.getId());
                    }
                } else {
                    // Skip if the action value is not an object
                    parser.skipChildren();
                }
            } else {
                // Skip if the item is not an object
                parser.skipChildren();
            }
        }
    }

    @SuppressWarnings("java:S3776") // Cognitive Complexity - parsing logic requires nested structure
    private static DocInfo extractDocInfo(JsonParser parser) throws IOException {
        var docInfo = DocInfo.builder();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            var innerFieldName = parser.currentName();
            parser.nextToken(); // Move to the value of the field

            if ("_id".equals(innerFieldName)) {
                docInfo.id(parser.getText());
            } else if ("result".equals(innerFieldName)) {
                docInfo.result(parser.getText());
            } else if ("status".equals(innerFieldName)) {
                docInfo.status(parser.getIntValue());
            } else if ("error".equals(innerFieldName)) {
                // Extract error type from error object
                if (parser.getCurrentToken() == JsonToken.START_OBJECT) {
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        var errorFieldName = parser.currentName();
                        parser.nextToken();
                        if ("type".equals(errorFieldName)) {
                            docInfo.errorType(parser.getText());
                        } else {
                            parser.skipChildren();
                        }
                    }
                } else {
                    parser.skipChildren();
                }
            } else {
                // Skip other fields or nested objects
                parser.skipChildren();
            }
        }
        return docInfo.build();
    }

    /**
     * Determines if a failed operation should be treated as success based on the allowlist.
     * 
     * @param docInfo The document information from the bulk response
     * @param allowlist Configuration for exceptions to treat as success
     * @return true if the failure should be treated as success, false otherwise
     */
    private static boolean isAllowedFailure(DocInfo docInfo, DocumentExceptionAllowlist allowlist) {
        return docInfo.getId() != null 
            && docInfo.getErrorType() != null 
            && allowlist.isAllowed(docInfo.getErrorType());
    }

    @Builder
    @Getter
    private static class DocInfo {
        private String id;
        private String result;
        private String errorType;
        private Integer status;
    }

    /**
     * Partition a bulk response into three buckets keyed by the item's position in
     * the original bulk request:
     * <ul>
     *   <li>{@code successPositions}: succeeded or allowlisted — drop from pending.</li>
     *   <li>{@code nonRetryableFailures}: failed with a type in
     *       {@link BulkDocErrorTypes#NON_RETRYABLE} (and not allowlisted) — emit to DLQ
     *       immediately and drop from pending so we don't keep retrying them.</li>
     *   <li>{@code retryableFailures}: any other failure — keep in pending for retry.
     *       If retries are exhausted, callers should emit these to the DLQ with
     *       {@code FailureClass.RETRYABLE_EXHAUSTED}.</li>
     * </ul>
     *
     * <p>Each failure carries the raw per-item response JSON so the DLQ record can
     * include the cluster's full error object verbatim.
     *
     * @return parsed partition, or {@code null} if the response could not be parsed
     *         (caller should fall back to retrying all docs).
     */
    public static ItemPartition partitionItems(String bulkResponse, DocumentExceptionAllowlist allowlist) {
        var partition = ItemPartition.builder();
        boolean foundItems = false;
        try (var parser = jsonFactory.createParser(bulkResponse)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if ("items".equals(parser.currentName())) {
                    scanItemsPartitioned(parser, partition, allowlist);
                    foundItems = true;
                } else {
                    parser.skipChildren();
                }
            }
        } catch (IOException e) {
            log.warn("Unable to parse bulk response for partitioning", e);
            return null;
        }
        if (!foundItems) {
            return null;
        }
        return partition.build();
    }

    private static void scanItemsPartitioned(
        JsonParser parser,
        ItemPartition.ItemPartitionBuilder partition,
        DocumentExceptionAllowlist allowlist
    ) throws IOException {
        if (parser.nextToken() != JsonToken.START_ARRAY) {
            throw new IOException("Expected 'items' to be an array");
        }
        int position = 0;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.getCurrentToken() != JsonToken.START_OBJECT) {
                continue;
            }
            // Capture the full per-item object as a JsonNode so we can preserve it verbatim.
            JsonNode itemNode = OBJECT_MAPPER.readTree(parser);
            classifyItem(itemNode, position, partition, allowlist);
            position++;
        }
    }

    private static void classifyItem(
        JsonNode itemNode,
        int position,
        ItemPartition.ItemPartitionBuilder partition,
        DocumentExceptionAllowlist allowlist
    ) {
        // Each item is { "<op>": { ...payload... } } with a single op key.
        if (!itemNode.isObject() || !itemNode.fields().hasNext()) {
            // Malformed entry — treat as retryable failure so we don't silently drop it.
            partition.retryableFailure(new ItemFailure(position, null, "malformed_response_item", itemNode));
            return;
        }
        var entry = itemNode.fields().next();
        JsonNode payload = entry.getValue();
        String docId = textOrNull(payload.get("_id"));
        String result = textOrNull(payload.get("result"));
        JsonNode errorNode = payload.get("error");
        String errorType = errorNode != null && errorNode.isObject() ? textOrNull(errorNode.get("type")) : null;

        if (result != null) {
            partition.successPosition(position);
            return;
        }
        if (errorType != null && allowlist.isAllowed(errorType)) {
            partition.successPosition(position);
            return;
        }
        var failure = new ItemFailure(position, docId, errorType, itemNode);
        if (errorType != null && BulkDocErrorTypes.NON_RETRYABLE.contains(errorType)) {
            partition.nonRetryableFailure(failure);
        } else {
            partition.retryableFailure(failure);
        }
    }

    private static String textOrNull(JsonNode node) {
        return node != null && !node.isNull() ? node.asText() : null;
    }

    /** One failed bulk item with enough context to be written to a DLQ. */
    @Value
    public static class ItemFailure {
        int position;
        String documentId;
        String errorType;
        /** Full per-item response object from the bulk API. */
        JsonNode responseItem;
    }

    /** Three-way classification of a bulk response, by item position. */
    @Value
    @Builder
    public static class ItemPartition {
        @lombok.Singular("successPosition") List<Integer> successPositions;
        @lombok.Singular("nonRetryableFailure") List<ItemFailure> nonRetryableFailures;
        @lombok.Singular("retryableFailure") List<ItemFailure> retryableFailures;
    }
}
