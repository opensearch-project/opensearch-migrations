package com.rfs.cms;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface AbstractedHttpClient extends AutoCloseable {
    String PUT_METHOD = "PUT";
    String POST_METHOD = "POST";
    String GET_METHOD = "GET";
    String HEAD_METHOD = "HEAD";

    interface AbstractHttpResponse {
        Stream<Map.Entry<String, String>> getHeaders();

        default byte[] getPayloadBytes() throws IOException {
            return getPayloadStream().readAllBytes();
        }

        default InputStream getPayloadStream() throws IOException {
            return new ByteArrayInputStream(getPayloadBytes());
        }

        String getStatusText();

        int getStatusCode();

        default String toDiagnosticString() {
            String payloadStr;
            try {
                payloadStr = Arrays.toString(getPayloadBytes());
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
        combinedHeaders.put("Content-Type", "application/json");
        combinedHeaders.put("Accept-Encoding", "identity");
        if (extraHeaders != null) {
            combinedHeaders.putAll(extraHeaders);
        }
        return makeRequest(method, path, combinedHeaders, body);
    }
}
