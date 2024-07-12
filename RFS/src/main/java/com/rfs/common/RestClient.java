package com.rfs.common;

import java.util.Base64;
import java.util.function.BiConsumer;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.SneakyThrows;
import com.rfs.netty.ReadMeteringHandler;
import com.rfs.netty.WriteMeteringHandler;
import com.rfs.tracing.IRfsContexts;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufMono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.tcp.SslProvider;
import reactor.netty.http.client.HttpClientRequest;
import reactor.netty.ByteBufMono;
import reactor.netty.Connection;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

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

    public Mono<Response> getAsync(String path, IRfsContexts.IRequestContext context) {
        return client
                .doOnRequest(addSizeMetricsHandlers(context))
                .get()
                .uri("/" + path)
                .responseSingle((response, bytes) -> bytes.asString()
                    .map(body -> new Response(response.status().code(), body, response.status().reasonPhrase())))
                .doOnError(t->context.addTraceException(t, true))
                .doFinally(r->context.close());
    }

    public Response get(String path, IRfsContexts.IRequestContext context) {
        return getAsync(path, context).block();
    }

    public Mono<Response> postAsync(String path, String body, IRfsContexts.IRequestContext context) {
        return client
                .doOnRequest(addSizeMetricsHandlers(context))
                .post()
                .uri("/" + path)
                .send(ByteBufMono.fromString(Mono.just(body)))
                .responseSingle((response, bytes) -> bytes.asString()
                    .map(b -> new Response(response.status().code(), b, response.status().reasonPhrase())))
                .doOnError(t->context.addTraceException(t, true))
                .doFinally(r->context.close());
    }

    public Mono<Response> putAsync(String path, String body, IRfsContexts.IRequestContext context) {
        return client
                .doOnRequest(addSizeMetricsHandlers(context))
                .put()
                .uri("/" + path)
                .send(ByteBufMono.fromString(Mono.just(body)))
                .responseSingle((response, bytes) -> bytes.asString()
                    .map(b -> new Response(response.status().code(), b, response.status().reasonPhrase())))
                .doOnError(t->context.addTraceException(t, true))
                .doFinally(r->context.close());
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
