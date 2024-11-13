package org.opensearch.migrations.bulkload.common;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.http.CompositeTransformer;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.GzipPayloadRequestTransformer;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.netty.ReadMeteringHandler;
import org.opensearch.migrations.bulkload.netty.WriteMeteringHandler;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientRequest;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.SslProvider;
import reactor.util.annotation.Nullable;

@Slf4j
public class RestClient {
    @Getter
    private final ConnectionContext connectionContext;
    private final HttpClient client;

    public static final String READ_METERING_HANDLER_NAME = "REST_CLIENT_READ_METERING_HANDLER";
    public static final String WRITE_METERING_HANDLER_NAME = "REST_CLIENT_WRITE_METERING_HANDLER";

    private static final String USER_AGENT_HEADER_NAME = HttpHeaderNames.USER_AGENT.toString();
    private static final String CONTENT_TYPE_HEADER_NAME = HttpHeaderNames.CONTENT_TYPE.toString();
    private static final String ACCEPT_ENCODING_HEADER_NAME = HttpHeaderNames.ACCEPT_ENCODING.toString();
    private static final String HOST_HEADER_NAME = HttpHeaderNames.HOST.toString();

    private static final String USER_AGENT = "RfsWorker-1.0";
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String GZIP_TYPE = "gzip";

    public RestClient(ConnectionContext connectionContext) {
        this(connectionContext, 0);
    }

    /**
     * @param maxConnections If &gt; 0, an HttpClient will be created with a provider
     *                       that uses this value for maxConnections.  Otherwise, a client
     *                       will be created with default values provided by Reactor.
     */
    public RestClient(ConnectionContext connectionContext, int maxConnections) {
        this(connectionContext, maxConnections <= 0
            ? HttpClient.create()
            : HttpClient.create(ConnectionProvider.create("RestClient", maxConnections)));
    }

    protected RestClient(ConnectionContext connectionContext, HttpClient httpClient) {
        this.connectionContext = connectionContext;

        SslProvider sslProvider;
        if (connectionContext.isInsecure()) {
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

        this.client = httpClient
            .secure(sslProvider)
            .baseUrl(connectionContext.getUri().toString())
            .keepAlive(true);
    }

    public static String getHostHeaderValue(ConnectionContext connectionContext) {
        String host = connectionContext.getUri().getHost();
        int port = connectionContext.getUri().getPort();
        ConnectionContext.Protocol protocol = connectionContext.getProtocol();

        if (ConnectionContext.Protocol.HTTP.equals(protocol)) {
            if (port == -1 || port == 80) {
                return host;
            }
        } else if (ConnectionContext.Protocol.HTTPS.equals(protocol)) {
            if (port == -1 || port == 443) {
                return host;
            }
        } else {
            throw new IllegalArgumentException("Unexpected protocol" + protocol);
        }
        return host + ":" + port;
    }

    public Mono<HttpResponse> asyncRequestWithFlatHeaderValues(HttpMethod method, String path, String body, Map<String, String> additionalHeaders,
                                                               IRfsContexts.IRequestContext context) {
        var convertedHeaders = additionalHeaders.entrySet().stream().collect(Collectors
            .toMap(Map.Entry::getKey, e -> List.of(e.getValue())));
        return asyncRequest(method, path, body, convertedHeaders, context);
    }


    public Mono<HttpResponse> asyncRequest(HttpMethod method, String path, String body, Map<String, List<String>> additionalHeaders,
                                           @Nullable IRfsContexts.IRequestContext context) {
        assert connectionContext.getUri() != null;
        Map<String, List<String>> headers = new HashMap<>();
        headers.put(USER_AGENT_HEADER_NAME, List.of(USER_AGENT));
        var hostHeaderValue = getHostHeaderValue(connectionContext);
        headers.put(HOST_HEADER_NAME, List.of(hostHeaderValue));
        if (body != null) {
            headers.put(CONTENT_TYPE_HEADER_NAME, List.of(JSON_CONTENT_TYPE));
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
        var contextCleanupRef = new AtomicReference<Runnable>(() -> {});
        // Support auto compressing payload if headers indicate support and payload is not compressed
        return new CompositeTransformer(
            new GzipPayloadRequestTransformer(),
            connectionContext.getRequestTransformer()
        ).transform(method.name(), path, headers, Mono.justOrEmpty(body)
                .map(b -> ByteBuffer.wrap(b.getBytes(StandardCharsets.UTF_8)))
            )
            .flatMap(transformedRequest ->
                client.doOnRequest((r, conn) -> contextCleanupRef.set(addSizeMetricsHandlersAndGetCleanup(context).apply(r, conn)))
                .headers(h -> transformedRequest.getHeaders().forEach(h::add))
                .compress(hasGzipResponseHeaders(transformedRequest.getHeaders()))
                .request(method)
                .uri("/" + path)
                .send(transformedRequest.getBody().map(Unpooled::wrappedBuffer))
                .responseSingle(
                    (response, bytes) -> bytes.asString()
                        .singleOptional()
                        .map(bodyOp -> new HttpResponse(
                            response.status().code(),
                            response.status().reasonPhrase(),
                            extractHeaders(response.responseHeaders()),
                            bodyOp.orElse(null)
                            ))
                )
            )
            .doOnError(t -> {
                if (context != null) {
                    context.addTraceException(t, true);
                }
            })
            .doOnTerminate(() -> contextCleanupRef.get().run());
    }


    public boolean supportsGzipCompression() {
        return connectionContext.isCompressionSupported();
    }

    public static void addGzipResponseHeaders(Map<String, List<String>> headers) {
        headers.put(ACCEPT_ENCODING_HEADER_NAME, List.of(GZIP_TYPE));
    }
    public static boolean hasGzipResponseHeaders(Map<String, List<String>> headers) {
        return headers.getOrDefault(ACCEPT_ENCODING_HEADER_NAME, List.of()).contains(GZIP_TYPE);
    }
    public static void addGzipRequestHeaders(Map<String, List<String>> headers) {
        headers.put(GzipPayloadRequestTransformer.CONTENT_ENCODING_HEADER_NAME,
            List.of(GzipPayloadRequestTransformer.GZIP_CONTENT_ENCODING_HEADER_VALUE));
    }
    public static boolean hasGzipRequestHeaders(Map<String, List<String>> headers) {
        return headers.getOrDefault(GzipPayloadRequestTransformer.CONTENT_ENCODING_HEADER_NAME, List.of())
            .contains(GzipPayloadRequestTransformer.GZIP_CONTENT_ENCODING_HEADER_VALUE);
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

    public Mono<HttpResponse> postAsync(
        String path,
        String body,
        Map<String, List<String>> additionalHeaders,
        IRfsContexts.IRequestContext context
    ) {
        return asyncRequest(HttpMethod.POST, path, body, additionalHeaders, context);
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
    addSizeMetricsHandlersAndGetCleanup(final IRfsContexts.IRequestContext ctx) {
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
