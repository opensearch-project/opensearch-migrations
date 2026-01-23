package org.opensearch.migrations.testutils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * This class brings up an HTTP(s) server with its constructor that returns responses
 * based upon a simple Function that is passed to the constructor.  This class can support
 * TLS, but only with an auto-generated self-signed cert.
 */
@Slf4j
public class SimpleNettyHttpServer implements AutoCloseable {

    public static final String LOCALHOST = "localhost";

    EventLoopGroup bossGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("simpleServerBoss"));
    EventLoopGroup workerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("simpleServerWorkerPool"));

    public final boolean useTls;
    public final int port;
    private Channel serverChannel;
    private Duration timeout;


    public static SimpleNettyHttpServer makeServer(
        boolean useTls,
        Function<HttpRequest, SimpleHttpResponse> makeContext
    ) throws Exception {
        return makeNettyServer(useTls, null, r -> makeContext.apply(new RequestToAdapter(r)));
    }

    public static SimpleNettyHttpServer makeNettyServer(
        boolean useTls,
        Function<FullHttpRequest, SimpleHttpResponse> makeContext
    ) throws Exception {
        return makeNettyServer(useTls, null, makeContext);
    }

    public static SimpleNettyHttpServer makeServer(
        boolean useTls,
        Duration readTimeout,
        Function<HttpRequest, SimpleHttpResponse> makeContext
    ) throws Exception {
        return makeNettyServer(useTls, readTimeout, r -> makeContext.apply(new RequestToAdapter(r)));
    }

    public static SimpleNettyHttpServer makeServer(
        SSLEngineSupplier sslEngineSupplier,
        Duration readTimeout,
        Function<HttpRequest, SimpleHttpResponse> makeContext
    ) throws Exception {
        return makeNettyServerWithSSL(sslEngineSupplier, readTimeout, r -> makeContext.apply(new RequestToAdapter(r)));
    }

    public static SimpleNettyHttpServer makeNettyServer(
        boolean useTls,
        Duration readTimeout,
        Function<FullHttpRequest, SimpleHttpResponse> makeContext
    ) throws Exception {
        SSLEngineSupplier sslEngineSupplier = null;
        if (useTls) {
            SSLContext javaSslContext = SelfSignedSSLContextBuilder.getSSLContext();
            sslEngineSupplier = (allocator) -> {
                SSLEngine engine = javaSslContext.createSSLEngine();
                engine.setUseClientMode(false);
                return engine;
            };
        }
        return makeNettyServerWithSSL(
            sslEngineSupplier,
            readTimeout,
            makeContext
        );
    }

    private static SimpleNettyHttpServer makeNettyServerWithSSL(
        SSLEngineSupplier sslEngineSupplier,
        Duration readTimeout,
        Function<FullHttpRequest, SimpleHttpResponse> makeContext
    ) throws PortFinder.ExceededMaxPortAssigmentAttemptException {
        var testServerRef = new AtomicReference<SimpleNettyHttpServer>();
        PortFinder.retryWithNewPortUntilNoThrow(port -> {
            try {
                testServerRef.set(new SimpleNettyHttpServer(port, readTimeout, makeContext, sslEngineSupplier));
            } catch (Exception e) {
                throw Lombok.sneakyThrow(e);
            }
        });
        return testServerRef.get();
    }

    public static class RequestToAdapter implements HttpRequest {
        private final FullHttpRequest request;

        public RequestToAdapter(FullHttpRequest request) {
            this.request = request;
        }

        @Override
        public String getVerb() {
            return request.method().toString();
        }

        @SneakyThrows
        @Override
        public URI getPath() {
            return new URI(request.uri());
        }

        @Override
        public String getVersion() {
            return request.protocolVersion().text();
        }

        @Override
        public List<Map.Entry<String, String>> getHeaders() { return request.headers().entries(); }
    }

    HttpHeaders convertHeaders(Map<String, String> headers) {
        var rval = new DefaultHttpHeaders();
        headers.entrySet().stream().forEach(kvp -> rval.add(kvp.getKey(), kvp.getValue()));
        return rval;
    }

    private SimpleChannelInboundHandler<FullHttpRequest> makeHandlerFromResponseContext(
        Function<HttpRequest, SimpleHttpResponse> responseBuilder) {
        return makeHandlerFromNettyResponseContext(r -> responseBuilder.apply(new RequestToAdapter(r)));
    }

    private SimpleChannelInboundHandler<FullHttpRequest> makeHandlerFromNettyResponseContext(
        Function<FullHttpRequest, SimpleHttpResponse> responseBuilder)
    {
        return new SimpleChannelInboundHandler<>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
                try {
                    if (req.decoderResult().isFailure()) {
                        ctx.close();
                        return;
                    }
                    var specifiedResponse = responseBuilder.apply(req);
                    var fullResponse = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.valueOf(specifiedResponse.statusCode, specifiedResponse.statusText),
                        Unpooled.wrappedBuffer(specifiedResponse.payloadBytes),
                        convertHeaders(specifiedResponse.headers),
                        new DefaultHttpHeaders()
                    );
                    log.atInfo().setMessage("writing {}").addArgument(fullResponse).log();
                    var cf = ctx.writeAndFlush(fullResponse);
                    log.atInfo().setMessage("wrote {}").addArgument(fullResponse).log();
                    cf.addListener(
                        f -> log.atInfo()
                            .setMessage("success={} finished writing {}")
                            .addArgument(f::isSuccess)
                            .addArgument(fullResponse)
                            .log()
                    );
                } catch (Exception e) {
                    log.atWarn().setCause(e).log("Closing connection due to exception");
                    ctx.close();
                }
            }
        };
    }

    @FunctionalInterface
    public interface SSLEngineSupplier {
        SSLEngine createSSLEngine(ByteBufAllocator allocator);
    }

    SimpleNettyHttpServer(
        int port,
        Duration timeout,
        Function<FullHttpRequest, SimpleHttpResponse> responseBuilder,
        SSLEngineSupplier sslEngineSupplier
    ) throws Exception {
        this.port = port;
        this.timeout = timeout;
        this.useTls = (sslEngineSupplier != null);

        var b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    var pipeline = ch.pipeline();
                    if (sslEngineSupplier != null) {
                        SSLEngine engine = sslEngineSupplier.createSSLEngine(ch.alloc());
                        pipeline.addFirst("SSL", new SslHandler(engine));
                    }
                    if (timeout != null) {
                        pipeline.addLast(new ReadTimeoutHandler(timeout.toMillis(), TimeUnit.MILLISECONDS));
                    }
                    pipeline.addLast(new LoggingHandler("A"));
                    pipeline.addLast(new HttpRequestDecoder());
                    pipeline.addLast(new LoggingHandler("B"));
                    pipeline.addLast(new HttpObjectAggregator(16 * 1024));
                    pipeline.addLast(new LoggingHandler("C"));
                    pipeline.addLast(new HttpResponseEncoder());
                    pipeline.addLast(makeHandlerFromNettyResponseContext(responseBuilder));
                }
            });
        serverChannel = b.bind(port).sync().channel();
    }

    public int port() {
        return port;
    }

    public URI localhostEndpoint() {
        try {
            return new URI((useTls ? "https" : "http"), null, LOCALHOST, port(), "/", null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Error building URI", e);
        }
    }

    @Override
    public void close() throws Exception {
        serverChannel.close();
        try {
            serverChannel.closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
}
