package com.rfs.cms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface AbstractedHttpClient {
    String PUT_METHOD = "PUT";
    String POST_METHOD = "POST";
    String GET_METHOD = "GET";
    String HEAD_METHOD = "HEAD";

    interface AbstractHttpResponse {
        Stream<Map.Entry<String, String>> getHeaders();

        byte[] getPayloadBytes() throws IOException;

        String getStatusText();

        int getStatusCode();

        default String toDiagnosticString() {
            String payloadStr;
            try {
                payloadStr = new String(getPayloadBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                payloadStr = "[EXCEPTION EVALUATING PAYLOAD]: " + e;
            }
            return getStatusText()
                + "/"
                + getStatusCode()
                + getHeaders().map(kvp -> kvp.getKey() + ": " + kvp.getValue())
                    .collect(Collectors.joining(";", "[", "]"))
                + payloadStr;
        }
    }

    AbstractHttpResponse makeRequest(String method, String path, Map<String, String> headers, String payload)
        throws IOException;

    default AbstractHttpResponse makeJsonRequest(
        String method,
        String path,
        Map<String, String> extraHeaders,
        String body
    ) throws IOException {
        var combinedHeaders = new LinkedHashMap<String, String>();
        if (body != null) {
            combinedHeaders.put("Content-Type", "application/json");
        }
        combinedHeaders.put("Accept-Encoding", "identity");
        if (extraHeaders != null) {
            combinedHeaders.putAll(extraHeaders);
        }
        return makeRequest(method, path, combinedHeaders, body);
    }
}
