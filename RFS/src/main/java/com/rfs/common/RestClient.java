package com.rfs.common;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import com.rfs.netty.ReadMeteringHandler;
import com.rfs.netty.WriteMeteringHandler;
import com.rfs.tracing.IRfsContexts;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufMono;
import reactor.netty.Connection;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientRequest;
import reactor.netty.tcp.SslProvider;

@Slf4j
public class RestClient {

    public static final String READ_METERING_HANDLER_NAME = "REST_CLIENT_READ_METERING_HANDLER";
    public static final String WRITE_METERING_HANDLER_NAME = "REST_CLIENT_WRITE_METERING_HANDLER";

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
        this(connectionDetails, HttpClient.create());
    }

    @SneakyThrows
    protected RestClient(ConnectionDetails connectionDetails, HttpClient httpClient) {
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

        this.client = httpClient.secure(sslProvider).baseUrl(connectionDetails.url).keepAlive(true)
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
        var contextCleanupRef = new AtomicReference<Runnable>();
        return client.doOnRequest((r, conn) -> contextCleanupRef.set(addSizeMetricsHandlers(context).apply(r, conn)))
            .get()
            .uri("/" + path)
            .responseSingle(
                (response, bytes) -> bytes.asString()
                    .map(body -> new Response(response.status().code(), body, response.status().reasonPhrase()))
            )
            .doOnError(t -> context.addTraceException(t, true))
            .doFinally(r -> contextCleanupRef.get().run());
    }

    public Response get(String path, IRfsContexts.IRequestContext context) {
        return getAsync(path, context).block();
    }

    public Mono<Response> postAsync(String path, String body, IRfsContexts.IRequestContext context) {
        var contextCleanupRef = new AtomicReference<Runnable>();
        return client.doOnRequest((r, conn) -> contextCleanupRef.set(addSizeMetricsHandlers(context).apply(r, conn)))
            .post()
            .uri("/" + path)
            .send(ByteBufMono.fromString(Mono.just(body)))
            .responseSingle(
                (response, bytes) -> bytes.asString()
                    .map(b -> new Response(response.status().code(), b, response.status().reasonPhrase()))
            )
            .doOnError(t -> context.addTraceException(t, true))
            .doFinally(r -> contextCleanupRef.get().run());
    }

    public Mono<Response> putAsync(String path, String body, IRfsContexts.IRequestContext context) {
        var contextCleanupRef = new AtomicReference<Runnable>();
        return client.doOnRequest((r, conn) -> contextCleanupRef.set(addSizeMetricsHandlers(context).apply(r, conn)))
            .put()
            .uri("/" + path)
            .send(ByteBufMono.fromString(Mono.just(body)))
            .responseSingle(
                (response, bytes) -> bytes.asString()
                    .map(b -> new Response(response.status().code(), b, response.status().reasonPhrase()))
            )
            .doOnError(t -> context.addTraceException(t, true))
            .doFinally(r -> contextCleanupRef.get().run());
    }

    public Response put(String path, String body, IRfsContexts.IRequestContext context) {
        return putAsync(path, body, context).block();
    }

    private static void removeIfPresent(ChannelPipeline p, String name) {
        var h = p.get(name);
        if (h != null) {
            p.remove(h);
        }
    }

    private static void addNewHandler(ChannelPipeline p, String name, ChannelHandler channelHandler) {
        removeIfPresent(p, name);
        p.addFirst(name, channelHandler);
    }

    private BiFunction<HttpClientRequest, Connection, Runnable>
    addSizeMetricsHandlers(final IRfsContexts.IRequestContext ctx) {
        return (r, conn) -> {
            var p = conn.channel().pipeline();
            addNewHandler(p, WRITE_METERING_HANDLER_NAME, new WriteMeteringHandler(ctx::addBytesSent));
            addNewHandler(p, READ_METERING_HANDLER_NAME, new ReadMeteringHandler(ctx::addBytesRead));
            return () -> {
                ctx.close();
                removeIfPresent(p, WRITE_METERING_HANDLER_NAME);
                removeIfPresent(p, READ_METERING_HANDLER_NAME);
            };
        };
    }
}
