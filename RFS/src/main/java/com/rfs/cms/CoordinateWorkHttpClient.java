package com.rfs.cms;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.rfs.common.RestClient;
import com.rfs.common.http.ConnectionContext;
import io.netty.handler.codec.http.HttpMethod;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CoordinateWorkHttpClient implements AbstractedHttpClient {

    private final RestClient restClient;

    @Getter
    @AllArgsConstructor
    public static class Response implements AbstractedHttpClient.AbstractHttpResponse {
        int statusCode;
        String statusText;
        List<Map.Entry<String, String>> headersList;
        byte[] payloadBytes;

        @Override
        public Stream<Map.Entry<String, String>> getHeaders() {
            return headersList.stream();
        }

        @Override
        public byte[] getPayloadBytes() {
            return payloadBytes;
        }
    }

    public CoordinateWorkHttpClient(ConnectionContext connectionContext) {
        this.restClient = new RestClient(connectionContext);
    }

    @Override
    public AbstractHttpResponse makeRequest(String method, String path, Map<String, String> headers, String payload) {
        HttpMethod httpMethod = HttpMethod.valueOf(method);
        var response = restClient.asyncRequestWithFlatHeaderValues(httpMethod, path, payload, headers, null).block();
        assert response != null;
        return new Response(
            response.statusCode,
            response.statusText,
            new ArrayList<>(response.headers.entrySet()),
            response.body != null ? response.body.getBytes(StandardCharsets.UTF_8) : null
        );
    }
}
