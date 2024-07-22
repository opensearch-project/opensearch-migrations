package com.rfs.common;

import java.util.Base64;
import java.util.Map;
import java.util.function.BiConsumer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import com.rfs.netty.ReadMeteringHandler;
import com.rfs.netty.WriteMeteringHandler;
import com.rfs.tracing.IRfsContexts;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.SneakyThrows;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufMono;
import reactor.netty.Connection;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientRequest;
import reactor.netty.tcp.SslProvider;

public class RestClient {
    public static class Response {
        public final int code;
        public final String body;
        public final String message;
        public final Map<String, String> headers;

        public Response(int responseCode, String responseBody, String responseMessage, Map<String, String> responseHeaders) {
            this.code = responseCode;
            this.body = responseBody;
            this.message = responseMessage;
            this.headers = responseHeaders;
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

    public Mono<Response> asyncRequest(HttpMethod method, String path, String body, IRfsContexts.IRequestContext context) {
        return client.doOnRequest(addSizeMetricsHandlers(context))
            .request(method)
            .uri("/" + path)
            .send(body != null ? ByteBufMono.fromString(Mono.just(body)) : Mono.empty())
            .responseSingle(
                (response, bytes) -> bytes.asString()
                    .map(b -> new Response(
                        response.status().code(),
                        b,
                        response.status().reasonPhrase(),
                        extractHeaders(response.responseHeaders())
                    ))
            )
            .doOnError(t -> context.addTraceException(t, true))
            .doFinally(r -> context.close());
    }

    private Map<String, String> extractHeaders(HttpHeaders headers) {
        return headers.entries().stream()
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (v1, v2) -> v1 + "," + v2
            ));
    }

    public Response get(String path, IRfsContexts.IRequestContext context) {
        return getAsync(path, context).block();
    }

    public Mono<Response> getAsync(String path, IRfsContexts.IRequestContext context) {
        return asyncRequest(HttpMethod.GET, path, null, context);
    }

    public Mono<Response> postAsync(String path, String body, IRfsContexts.IRequestContext context) {
        return asyncRequest(HttpMethod.POST, path, body, context);
    }

    public Response post(String path, String body, IRfsContexts.IRequestContext context) {
        return postAsync(path, body, context).block();
    }

    public Mono<Response> putAsync(String path, String body, IRfsContexts.IRequestContext context) {
        return asyncRequest(HttpMethod.PUT, path, body, context);
    }

    public Response put(String path, String body, IRfsContexts.IRequestContext context) {
        return putAsync(path, body, context).block();
    }

    private BiConsumer<HttpClientRequest, Connection> addSizeMetricsHandlers(final IRfsContexts.IRequestContext ctx) {
        return (r, conn) -> {
            conn.channel().pipeline().addFirst(new WriteMeteringHandler(ctx::addBytesSent));
            conn.channel().pipeline().addFirst(new ReadMeteringHandler(ctx::addBytesRead));
        };
    }
}
