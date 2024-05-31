package com.rfs.common;

import java.util.Base64;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

import com.rfs.netty.ReadMeteringHandler;
import com.rfs.netty.WriteMeteringHandler;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientRequest;
import reactor.netty.ByteBufMono;
import reactor.netty.Connection;

public class RestClient {

    private static final AttributeKey<String> ATTRIBUTE_HTTP_METHOD = AttributeKey.stringKey("HttpMethod");
    private static final AttributeKey<String> ATTRIBUTE_PATH = AttributeKey.stringKey("Path");
    private static final LongCounter readBytesCounter;
    private static final LongCounter writeBytesCounter;
    static {
        final var meter = GlobalOpenTelemetry.getMeter("RestClient");
        readBytesCounter = meter.counterBuilder("readBytes")
            .setDescription("Counts the number of bytes read")
            .setUnit("1")
            .build();
        writeBytesCounter = meter.counterBuilder("writeBytes")
            .setDescription("Counts the number of bytes written")
            .setUnit("1")
            .build();
    }

    public static class Response {
        public final int code;
        public final String body;
        public final String message;

        @WithSpan
        public Response(@SpanAttribute("responseCode") int responseCode, String responseBody, String responseMessage) {
            this.code = responseCode;
            this.body = responseBody;
            this.message = responseMessage;
        }
    }

    public final ConnectionDetails connectionDetails;
    private final HttpClient client;

    public RestClient(ConnectionDetails connectionDetails) {
        this.connectionDetails = connectionDetails;

        this.client = HttpClient.create()
            .baseUrl(connectionDetails.url)
            .headers(h -> {
                h.add("Content-Type", "application/json");
                h.add("User-Agent", "RfsWorker-1.0");
                if (connectionDetails.authType == ConnectionDetails.AuthType.BASIC) {
                    String credentials = connectionDetails.username + ":" + connectionDetails.password;
                    String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());
                    h.add("Authorization", "Basic " + encodedCredentials);
                }
            });
    }

    @WithSpan
    public Mono<Response> getAsync(String path) {
        return client
            .doOnRequest(sizeMetricsHandler("GET", path))
            .get()
            .uri("/" + path)
            .responseSingle((response, bytes) -> bytes.asString()
            .map(body -> new Response(response.status().code(), body, response.status().reasonPhrase())))
            .doOnError(Span.current()::recordException);
    }

    public Response get(String path) {
        return getAsync(path).block();
    }

    public Mono<Response> postAsync(String path, String body) {
        return client
            .doOnRequest(sizeMetricsHandler("POST", path))
            .post()
            .uri("/" + path)
            .send(ByteBufMono.fromString(Mono.just(body)))
            .responseSingle((response, bytes) -> bytes.asString()
            .map(b -> new Response(response.status().code(), b, response.status().reasonPhrase())))
            .doOnError(Span.current()::recordException);
    }

    public Mono<Response> putAsync(String path, String body) {
        return client
            .doOnRequest(sizeMetricsHandler("PUT", path))
            .put()
            .uri("/" + path)
            .send(ByteBufMono.fromString(Mono.just(body)))
            .responseSingle((response, bytes) -> bytes.asString()
            .map(b -> new Response(response.status().code(), b, response.status().reasonPhrase())))
            .doOnError(Span.current()::recordException);
    }

    public Response put(String path, String body) {
        return putAsync(path, body).block();
    }

    private BiConsumer<? super HttpClientRequest, ? super Connection> sizeMetricsHandler(final String httpMethod, final String path) {
        return (r, conn) -> {
            conn.channel().pipeline().addFirst(new WriteMeteringHandler(value -> writeBytesCounter.add(value, Attributes.of(ATTRIBUTE_HTTP_METHOD, httpMethod, ATTRIBUTE_PATH, path))));
            conn.channel().pipeline().addFirst(new ReadMeteringHandler(value -> readBytesCounter.add(value, Attributes.of(ATTRIBUTE_HTTP_METHOD, httpMethod, ATTRIBUTE_PATH, path))));
        };
    }

}