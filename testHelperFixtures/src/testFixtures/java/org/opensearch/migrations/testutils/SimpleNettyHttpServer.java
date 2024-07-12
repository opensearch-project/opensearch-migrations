package org.opensearch.migrations.testutils;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import io.netty.bootstrap.ServerBootstrap;
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
        Function<HttpRequestFirstLine, SimpleHttpResponse> makeContext
    ) throws PortFinder.ExceededMaxPortAssigmentAttemptException {
        return makeServer(useTls, null, makeContext);
    }

    public static SimpleNettyHttpServer makeServer(
        boolean useTls,
        Duration readTimeout,
        Function<HttpRequestFirstLine, SimpleHttpResponse> makeContext
    ) throws PortFinder.ExceededMaxPortAssigmentAttemptException {
        var testServerRef = new AtomicReference<SimpleNettyHttpServer>();
        PortFinder.retryWithNewPortUntilNoThrow(port -> {
            try {
                testServerRef.set(new SimpleNettyHttpServer(useTls, port, readTimeout, makeContext));
            } catch (Exception e) {
                throw Lombok.sneakyThrow(e);
            }
        });
        return testServerRef.get();
    }

    private static class RequestToFirstLineAdapter implements HttpRequestFirstLine {
        private final FullHttpRequest request;

        public RequestToFirstLineAdapter(FullHttpRequest request) {
            this.request = request;
        }

        @Override
        public String verb() {
            return request.method().toString();
        }

        @SneakyThrows
        @Override
        public URI path() {
            return new URI(request.uri());
        }

        @Override
        public String version() {
            return request.protocolVersion().text();
        }
    }

    HttpHeaders convertHeaders(Map<String, String> headers) {
        var rval = new DefaultHttpHeaders();
        headers.entrySet().stream().forEach(kvp -> rval.add(kvp.getKey(), kvp.getValue()));
        return rval;
    }

    private SimpleChannelInboundHandler<FullHttpRequest> makeHandlerFromResponseContext(
        Function<HttpRequestFirstLine, SimpleHttpResponse> responseBuilder
    ) {
        return new SimpleChannelInboundHandler<>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
                try {
                    if (req.decoderResult().isFailure()) {
                        ctx.close();
                        return;
                    }
                    var specifiedResponse = responseBuilder.apply(new RequestToFirstLineAdapter(req));
                    var fullResponse = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.valueOf(specifiedResponse.statusCode, specifiedResponse.statusText),
                        Unpooled.wrappedBuffer(specifiedResponse.payloadBytes),
                        convertHeaders(specifiedResponse.headers),
                        new DefaultHttpHeaders()
                    );
                    log.atInfo().setMessage(() -> "writing " + fullResponse).log();
                    var cf = ctx.writeAndFlush(fullResponse);
                    log.atInfo().setMessage(() -> "wrote " + fullResponse).log();
                    cf.addListener(
                        f -> log.atInfo()
                            .setMessage(() -> "success=" + f.isSuccess() + " finished writing " + fullResponse)
                            .log()
                    );
                } catch (Exception e) {
                    log.atWarn().setCause(e).log("Closing connection due to exception");
                    ctx.close();
                }
            }
        };
    }

    SimpleNettyHttpServer(
        boolean useTLS,
        int port,
        Duration timeout,
        Function<HttpRequestFirstLine, SimpleHttpResponse> responseBuilder
    ) throws Exception {
        this.useTls = useTLS;
        this.port = port;
        this.timeout = timeout;
        final SSLContext javaSSLContext = useTLS ? SelfSignedSSLContextBuilder.getSSLContext() : null;

        var b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    var pipeline = ch.pipeline();
                    if (javaSSLContext != null) {
                        SSLEngine engine = javaSSLContext.createSSLEngine();
                        engine.setUseClientMode(false);
                        pipeline.addFirst("SSL", new SslHandler(engine));
                    }
                    if (timeout != null) {
                        pipeline.addLast(new ReadTimeoutHandler(timeout.toMillis(), TimeUnit.MILLISECONDS));
                    }
                    pipeline.addLast(new HttpRequestDecoder());
                    pipeline.addLast(new HttpObjectAggregator(16 * 1024));
                    pipeline.addLast(new HttpResponseEncoder());
                    pipeline.addLast(makeHandlerFromResponseContext(responseBuilder));
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
