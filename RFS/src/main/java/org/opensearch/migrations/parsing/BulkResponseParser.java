package org.opensearch.migrations.parsing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
public class BulkResponseParser {
    private static JsonFactory jsonFactory = new JsonFactory();

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
}
