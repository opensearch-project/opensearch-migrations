package com.rfs.cms;

import com.rfs.common.RestClient;
import com.rfs.common.ConnectionDetails;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.stream.Stream;

public class RestClientWrapperHttpClient implements AbstractedHttpClient {

    private final RestClient restClient;

    public RestClientWrapperHttpClient(ConnectionDetails connectionDetails) {
        this.restClient = new RestClient(connectionDetails);
    }

    @Override
    public AbstractHttpResponse makeRequest(String method, String path, Map<String, String> headers, String payload) {
        RestClient.Response response;
        switch (method.toUpperCase()) {
            case HEAD_METHOD:
                response = restClient.head(path, null);
                break;
            case GET_METHOD:
                response = restClient.get(path, null);
                break;
            case POST_METHOD:
                response = restClient.post(path, payload, null);
                break;
            case PUT_METHOD:
                response = restClient.put(path, payload, null);
                break;
            default:
                throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        }

        return new AbstractHttpResponse() {
            @Override
            public Stream<Map.Entry<String, String>> getHeaders() {
                // RestClient doesn't provide access to response headers, so we return an empty stream
                return Stream.empty();
            }

            @Override
            public byte[] getPayloadBytes() {
                return response.body.getBytes();
            }

            @Override
            public InputStream getPayloadStream() {
                return new ByteArrayInputStream(getPayloadBytes());
            }

            @Override
            public String getStatusText() {
                return response.message;
            }

            @Override
            public int getStatusCode() {
                return response.code;
            }
        };
    }

    @Override
    public void close() {
        // RestClient doesn't have a close method, so we don't need to do anything here
    }
}
