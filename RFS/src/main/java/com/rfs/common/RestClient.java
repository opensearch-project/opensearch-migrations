package com.rfs.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.rfs.netty.ReadMeteringHandler;
import com.rfs.netty.WriteMeteringHandler;
import com.rfs.tracing.IRfsContexts;
import io.netty.buffer.Unpooled;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.ByteBufMono;

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

    public Mono<Response> getAsync(String path, IRfsContexts.IRequestContext context) {
        return client
                .doOnRequest((r, conn) -> {
                    conn.channel().pipeline().addFirst(new WriteMeteringHandler(context::addBytesSent));
                    conn.channel().pipeline().addFirst(new ReadMeteringHandler(context::addBytesRead));
                })
                .get()
                .uri("/" + path)
                .responseSingle((response, bytes) -> bytes.asString()
                .map(body -> new Response(response.status().code(), body, response.status().reasonPhrase())))
                .doOnError(t->context.addTraceException(t, true))
                .doFinally(r->context.close())
                ;
    }

    public Response get(String path, IRfsContexts.IRequestContext context) {
        return getAsync(path, context).block();
    }

    public Mono<Response> postAsync(String path, String body, IRfsContexts.IRequestContext context) {
        return client
                .doOnRequest((r, conn) -> {
                    conn.channel().pipeline().addFirst(new WriteMeteringHandler(context::addBytesSent));
                    conn.channel().pipeline().addFirst(new ReadMeteringHandler(context::addBytesRead));
                })
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
                .doOnRequest((r, conn) -> {
                    conn.channel().pipeline().addFirst(new WriteMeteringHandler(context::addBytesSent));
                    conn.channel().pipeline().addFirst(new ReadMeteringHandler(context::addBytesRead));
                })
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
}