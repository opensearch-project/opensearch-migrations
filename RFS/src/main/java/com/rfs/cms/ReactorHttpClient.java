package com.rfs.cms;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.rfs.common.http.ConnectionContext;
import com.rfs.common.http.RestClient;
import io.netty.handler.codec.http.HttpMethod;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
public class ReactorHttpClient implements AbstractedHttpClient {

    private RestClient restClient;

    @Getter
    @AllArgsConstructor
    public static class Response implements AbstractedHttpClient.AbstractHttpResponse {
        List<Map.Entry<String, String>> headersList;
        String statusText;
        int statusCode;
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

    public ReactorHttpClient(ConnectionContext connectionContext) {
        this.restClient = new RestClient(connectionContext);
    }

    @Override
    public AbstractHttpResponse makeRequest(String method, String path, Map<String, String> headers, String payload)
        throws IOException {
        Mono<RestClient.Response> responseMono;
        HttpMethod httpMethod = HttpMethod.valueOf(method);
        responseMono = restClient.asyncRequest(httpMethod, path, payload, null);
        RestClient.Response response = responseMono.block();

        return new Response(
            new ArrayList<>(response.headers.entrySet()),
            response.message,
            response.code,
            response.body.getBytes()
        );
    }

    @Override
    public void close() throws Exception {
        // RestClient doesn't have a close method, so this is a no-op
    }
}
