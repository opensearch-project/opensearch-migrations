package org.opensearch.migrations.transform;

import java.util.Optional;

import lombok.NonNull;

public class TestRequestBuilder {
    public static String makePutIndexRequest(String indexName, Boolean useMultiple, Boolean includeTypeName) {
        var uri = formatCreateIndexUri(indexName, includeTypeName);
        return "{\n" +
            "  \"" + JsonKeysForHttpMessage.METHOD_KEY + "\": \"PUT\",\n" +
            "  \"" + JsonKeysForHttpMessage.PROTOCOL_KEY + "\": \"HTTP/1.1\",\n" +
            "  \"" + JsonKeysForHttpMessage.URI_KEY + "\": \"" + uri + "\",\n" +
            "  \"" + JsonKeysForHttpMessage.PAYLOAD_KEY + "\": {\n" +
            "    \"" + JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY + "\": " +
            "{\n" +
            "  \"settings\" : {\n" +
            "    \"number_of_shards\" : 1\n" +
            "  }," +
            "  \"mappings\": {\n" +
            "    \"user\": {\n" +
            "      \"properties\": {\n" +
            "        \"name\": { \"type\": \"text\" },\n" +
            "        \"user_name\": { \"type\": \"keyword\" },\n" +
            "        \"email\": { \"type\": \"keyword\" }\n" +
            "      }\n" +
            "    }" +
            (useMultiple ? ",\n" +
                "    \"tweet\": {\n" +
                "      \"properties\": {\n" +
                "        \"content\": { \"type\": \"text\" },\n" +
                "        \"user_name\": { \"type\": \"keyword\" },\n" +
                "        \"tweeted_at\": { \"type\": \"date\" }\n" +
                "      }\n" +
                "    },\n" +
                "    \"following\": {\n" +
                "      \"properties\": {\n" +
                "        \"count\": { \"type\": \"integer\" },\n" +
                "        \"followers\": { \"type\": \"string\" }\n" +
                "      }\n" +
                "    }\n"
                : "") +
            "  }\n" +
            "}" +
            "\n" +
            "  }\n" +
            "}";
    }

    public static @NonNull String formatCreateIndexUri(String indexName, Boolean includeTypeName) {
        return "/" + indexName +
            Optional.ofNullable(includeTypeName).map(b -> "?include_type_name=" + b).orElse("");
    }
}
