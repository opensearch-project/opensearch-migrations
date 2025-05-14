package org.opensearch.migrations.parsing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
                        scanItems(parser, successfulDocumentIds);
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

    private static void scanItems(JsonParser parser, List<String> successfulDocumentIds) throws IOException {
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

    private static DocInfo extractDocInfo(JsonParser parser) throws IOException {
        var docInfo = DocInfo.builder();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            var innerFieldName = parser.currentName();
            parser.nextToken(); // Move to the value of the field

            if ("_id".equals(innerFieldName)) {
                docInfo.id(parser.getText());
            } else if ("result".equals(innerFieldName)) {
                docInfo.result(parser.getText());
            } else {
                // Skip other fields or nested objects
                parser.skipChildren();
            }
        }
        return docInfo.build();
    }

    @Builder
    @Getter
    private static class DocInfo {
        private String id;
        private String result;
    }
}
