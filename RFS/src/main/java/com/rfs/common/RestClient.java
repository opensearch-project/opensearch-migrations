package com.rfs.common;

import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;

import com.rfs.common.http.ConnectionContext;
import com.rfs.common.http.HttpResponse;
import com.rfs.common.http.RequestTransformer;
import com.rfs.netty.ReadMeteringHandler;
import com.rfs.netty.WriteMeteringHandler;
import com.rfs.tracing.IRfsContexts;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientRequest;
import reactor.netty.tcp.SslProvider;
import reactor.util.annotation.Nullable;

public class RestClient {
    private final ConnectionContext connectionContext;
    private final HttpClient client;
    private final RequestTransformer authTransformer;

    public static final String READ_METERING_HANDLER_NAME = "REST_CLIENT_READ_METERING_HANDLER";
    public static final String WRITE_METERING_HANDLER_NAME = "REST_CLIENT_WRITE_METERING_HANDLER";
    private static final Map.Entry<String, List<String>> USER_AGENT_HEADER = new AbstractMap.SimpleEntry<>("User-Agent", List.of("RfsWorker-1.0"));
    private static final Map.Entry<String, List<String>> JSON_CONTENT_TYPE_HEADER = new AbstractMap.SimpleEntry<>("Content-Type", List.of("application/json"));
    private static final String HOST_HEADER_KEY = "Host";

    public RestClient(ConnectionContext connectionContext) {
        this(connectionContext, HttpClient.create());
    }

    protected RestClient(ConnectionContext connectionContext, HttpClient httpClient) {
        this.connectionContext = connectionContext;
        this.authTransformer = connectionContext.getAuthTransformer();

        SslProvider sslProvider;
        if (connectionContext.insecure) {
            try {
                SslContext sslContext = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
                sslProvider = SslProvider.builder().sslContext(sslContext).handlerConfigurator(sslHandler -> {
                    SSLEngine engine = sslHandler.engine();
                    SSLParameters sslParameters = engine.getSSLParameters();
                    sslParameters.setEndpointIdentificationAlgorithm(null);
                    engine.setSSLParameters(sslParameters);
                }).build();
            } catch (SSLException e) {
                throw new IllegalStateException("Unable to construct SslProvider", e);
            }
        } else {
            sslProvider = SslProvider.defaultClientProvider();
        }

        this.client = httpClient.secure(sslProvider).baseUrl(connectionContext.getUrl()).keepAlive(true);
    }

    public static String getHostHeader(String urlString) {
        try {
            URL url = new URL(urlString);
            String host = url.getHost();
            int port = url.getPort();
            String protocol = url.getProtocol();

            if (port == -1 || (protocol.equals("http") && port == 80) || (protocol.equals("https") && port == 443)) {
                return host;
            } else {
                return host + ":" + port;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public Mono<HttpResponse> asyncRequestWithStringHeaderValues(HttpMethod method, String path, String body, Map<String, String> additionalHeaders,
                                                                 IRfsContexts.IRequestContext context) {
        var convertedHeaders = additionalHeaders.entrySet().stream().collect(Collectors
            .toMap(Map.Entry::getKey, e -> List.of(e.getValue())));
        return asyncRequest(method, path, body, convertedHeaders, context);
    }


    public Mono<HttpResponse> asyncRequest(HttpMethod method, String path, String body, Map<String, List<String>> additionalHeaders,
                                           @Nullable IRfsContexts.IRequestContext context) {
        Map<String, List<String>> headers = new HashMap<>();
        headers.put(USER_AGENT_HEADER.getKey(), USER_AGENT_HEADER.getValue());
        headers.put(HOST_HEADER_KEY, List.of(getHostHeader(connectionContext.getUrl())));
        if (body != null) {
            headers.put(JSON_CONTENT_TYPE_HEADER.getKey(), JSON_CONTENT_TYPE_HEADER.getValue());
        }
        if (additionalHeaders != null) {
            additionalHeaders.forEach((key, value) -> {
                    if (headers.containsKey(key.toLowerCase())) {
                        headers.put(key.toLowerCase(), value);
                    } else {
                        headers.put(key, value);
                    }
                });
        }
        var contextCleanupRef = new AtomicReference<Runnable>();
        return authTransformer.transform(method.name(), path, headers, Mono.justOrEmpty(body)
                .map(b -> ByteBuffer.wrap(b.getBytes(StandardCharsets.UTF_8)))
            )
            .flatMap(transformedRequest ->
                client.doOnRequest((r, conn) -> contextCleanupRef.set(addSizeMetricsHandlers(context).apply(r, conn)))
                .headers(h -> transformedRequest.getHeaders().forEach(h::add))
                .request(method)
                .uri("/" + path)
                .send(transformedRequest.getBody().map(Unpooled::wrappedBuffer))
                .responseSingle(
                    (response, bytes) -> bytes.asString()
                        .singleOptional()
                        .map(bodyOp -> new HttpResponse(
                            response.status().code(),
                            bodyOp.orElse(null),
                            response.status().reasonPhrase(),
                            extractHeaders(response.responseHeaders())
                        ))
                ))
            .doOnError(t -> {
                if (context != null) {
                    context.addTraceException(t, true);
                }
            })
            .doOnTerminate(() -> contextCleanupRef.get().run());
    }

    private Map<String, String> extractHeaders(HttpHeaders headers) {
        return headers.entries().stream()
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (v1, v2) -> v1 + "," + v2
            ));
    }

    public HttpResponse get(String path, IRfsContexts.IRequestContext context) {
        return getAsync(path, context).block();
    }

    public Mono<HttpResponse> getAsync(String path, IRfsContexts.IRequestContext context) {
        return asyncRequest(HttpMethod.GET, path, null, null, context);
    }

    public Mono<HttpResponse> postAsync(String path, String body, IRfsContexts.IRequestContext context) {
        return asyncRequest(HttpMethod.POST, path, body, null, context);
    }

    public HttpResponse post(String path, String body, IRfsContexts.IRequestContext context) {
        return postAsync(path, body, context).block();
    }

    public Mono<HttpResponse> putAsync(String path, String body, IRfsContexts.IRequestContext context) {
        return asyncRequest(HttpMethod.PUT, path, body, null, context);
    }

    public HttpResponse put(String path, String body, IRfsContexts.IRequestContext context) {
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
        if (ctx == null) {
            return (r, conn) -> () -> {};
        }
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
