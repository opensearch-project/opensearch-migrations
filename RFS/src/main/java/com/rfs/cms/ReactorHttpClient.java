package com.rfs.cms;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import com.rfs.common.ConnectionDetails;
import io.netty.handler.codec.http.HttpMethod;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufMono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientResponse;

@Slf4j
public class ReactorHttpClient implements AbstractedHttpClient {

    private HttpClient client;

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
    }

    public ReactorHttpClient(ConnectionDetails connectionDetails) {
        this.client = HttpClient.create().baseUrl(connectionDetails.url).headers(h -> {
            h.add("Content-Type", "application/json");
            h.add("User-Agent", "RfsWorker-1.0");
            if (connectionDetails.authType == ConnectionDetails.AuthType.BASIC) {
                String credentials = connectionDetails.username + ":" + connectionDetails.password;
                String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());
                h.add("Authorization", "Basic " + encodedCredentials);
            }
        });
    }

    @Override
    public AbstractHttpResponse makeRequest(String method, String path, Map<String, String> headers, String payload)
        throws IOException {
        var requestSender = client.request(HttpMethod.valueOf(method)).uri("/" + path);
        BiFunction<HttpClientResponse, ByteBufMono, Mono<Response>> responseWrapperFunction = (response, bytes) -> {
            try {
                log.info("Received response with status: " + response.status());
                log.info("Response headers: " + response.responseHeaders().entries());

                return bytes.asByteArray().map(b -> {
                    try {
                        log.info("Making wrapped response with status: " + response.status());

                        return new Response(
                            new ArrayList<>(response.responseHeaders().entries()),
                            response.status().reasonPhrase(),
                            response.status().code(),
                            b
                        );
                    } catch (Exception e) {
                        log.atError().setCause(e).setMessage("Caught exception").log();
                        throw e;
                    }
                })
                    .or(
                        Mono.fromSupplier(
                            () -> new Response(
                                new ArrayList<>(response.responseHeaders().entries()),
                                response.status().reasonPhrase(),
                                response.status().code(),
                                null
                            )
                        )
                    );
            } catch (Exception e) {
                log.atError().setCause(e).setMessage("Caught exception").log();
                throw e;
            }
        };
        var monoResponse = payload != null
            ? requestSender.send(ByteBufMono.fromString(Mono.just(payload))).responseSingle(responseWrapperFunction)
            : requestSender.responseSingle(responseWrapperFunction);
        return monoResponse.block();
    }

    @Override
    public void close() throws Exception {}
}
