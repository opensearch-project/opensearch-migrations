package com.rfs.common;

import java.util.Base64;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.SneakyThrows;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufMono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.tcp.SslProvider;

public class RestClient {
    public static class Response {
        public final int code;
        public final String body;
        public final String message;

        public Response(int responseCode, String responseBody, String responseMessage) {
            this.code = responseCode;
            this.body = responseBody;
            this.message = responseMessage;
        }
    }

    public final ConnectionDetails connectionDetails;
    private final HttpClient client;

    @SneakyThrows
    public RestClient(ConnectionDetails connectionDetails) {
        this.connectionDetails = connectionDetails;

        SslProvider sslProvider;
        if (connectionDetails.insecure) {
            SslContext sslContext = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();

            sslProvider = SslProvider.builder().sslContext(sslContext).handlerConfigurator(sslHandler -> {
                SSLEngine engine = sslHandler.engine();
                SSLParameters sslParameters = engine.getSSLParameters();
                sslParameters.setEndpointIdentificationAlgorithm(null);
                engine.setSSLParameters(sslParameters);
            }).build();
        } else {
            sslProvider = SslProvider.defaultClientProvider();
        }

        this.client = HttpClient.create().secure(sslProvider).baseUrl(connectionDetails.url).headers(h -> {
            h.add("Content-Type", "application/json");
            h.add("User-Agent", "RfsWorker-1.0");
            if (connectionDetails.authType == ConnectionDetails.AuthType.BASIC) {
                String credentials = connectionDetails.username + ":" + connectionDetails.password;
                String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());
                h.add("Authorization", "Basic " + encodedCredentials);
            }
        });
    }

    public Mono<Response> getAsync(String path) {
        return client.get()
            .uri("/" + path)
            .responseSingle(
                (response, bytes) -> bytes.asString()
                    .map(body -> new Response(response.status().code(), body, response.status().reasonPhrase()))
            );
    }

    public Response get(String path) {
        return getAsync(path).block();
    }

    public Mono<Response> postAsync(String path, String body) {
        return client.post()
            .uri("/" + path)
            .send(ByteBufMono.fromString(Mono.just(body)))
            .responseSingle(
                (response, bytes) -> bytes.asString()
                    .map(b -> new Response(response.status().code(), b, response.status().reasonPhrase()))
            );
    }

    public Mono<Response> putAsync(String path, String body) {
        return client.put()
            .uri("/" + path)
            .send(ByteBufMono.fromString(Mono.just(body)))
            .responseSingle(
                (response, bytes) -> bytes.asString()
                    .map(b -> new Response(response.status().code(), b, response.status().reasonPhrase()))
            );
    }

    public Response put(String path, String body) {
        return putAsync(path, body).block();
    }
}
