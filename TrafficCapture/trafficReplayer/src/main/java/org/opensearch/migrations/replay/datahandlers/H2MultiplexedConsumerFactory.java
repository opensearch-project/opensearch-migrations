package org.opensearch.migrations.replay.datahandlers;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Frame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * — multiplexes many concurrent H2 requests on a single shared parent
 * H2 connection. Each call to {@link #createConsumer()} returns a lightweight
 * per-request consumer that opens its own H2 stream sub-channel via
 * {@link Http2StreamChannelBootstrap}; the shared parent connection is the only
 * thing that gets reused.
 *
 * <p>Compared to {@link H2NettyPacketToHttpConsumer} (which establishes its own
 * parent connection per instance), this factory is the right shape for plugging into
 * {@code ConnectionReplaySession}'s lifecycle: open one parent per session, mint many
 * stream-scoped consumers from it.
 *
 * <p>Lifecycle: {@link #open()} establishes the parent (TLS+ALPN handshake);
 * {@link #close()} shuts it down + the EventLoopGroup. Failure to call open before
 * createConsumer will surface a clear error.
 */
@Slf4j
public class H2MultiplexedConsumerFactory implements AutoCloseable {

    private final URI targetUri;
    private final boolean allowInsecure;
    private final Duration timeout;
    private final EventLoopGroup eventLoopGroup;
    private volatile Channel parentChannel;

    public H2MultiplexedConsumerFactory(URI targetUri, boolean allowInsecure, Duration timeout) {
        this.targetUri = targetUri;
        this.allowInsecure = allowInsecure;
        this.timeout = timeout;
        this.eventLoopGroup = new NioEventLoopGroup(1);
    }

    /** Establish the parent H2 connection. Must be called before {@link #createConsumer()}. */
    public void open() throws Exception {
        if (parentChannel != null) return;
        var ctxBuilder = SslContextBuilder.forClient()
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2,
                        ApplicationProtocolNames.HTTP_1_1));
        if (allowInsecure) ctxBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
        SslContext sslCtx = ctxBuilder.build();

        var handshakeFuture = new CompletableFuture<Void>();
        var b = new Bootstrap()
                .group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        var engine = sslCtx.newEngine(ch.alloc());
                        engine.setUseClientMode(true);
                        var sslHandler = new SslHandler(engine);
                        ch.pipeline().addLast(sslHandler);
                        ch.pipeline().addLast(new io.netty.handler.ssl.ApplicationProtocolNegotiationHandler(
                                ApplicationProtocolNames.HTTP_1_1) {
                            @Override
                            protected void configurePipeline(ChannelHandlerContext c, String protocol) {
                                if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                                    c.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
                                    c.pipeline().addLast(new Http2MultiplexHandler(
                                            new ChannelInitializer<Channel>() {
                                                @Override protected void initChannel(Channel ignored) {}
                                            }));
                                    handshakeFuture.complete(null);
                                } else {
                                    handshakeFuture.completeExceptionally(new IllegalStateException(
                                            "Target negotiated " + protocol + ", expected h2"));
                                }
                            }
                        });
                    }
                });
        int port = targetUri.getPort() < 0 ? 443 : targetUri.getPort();
        var connectFuture = b.connect(targetUri.getHost(), port);
        connectFuture.addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) handshakeFuture.completeExceptionally(f.cause());
            else this.parentChannel = f.channel();
        });
        connectFuture.sync();
        handshakeFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Returns a per-request consumer that opens its own H2 stream sub-channel. */
    public IPacketFinalizingConsumer<AggregatedRawResponse> createConsumer() {
        if (parentChannel == null) {
            throw new IllegalStateException("Factory not opened — call open() first");
        }
        return new StreamConsumer(parentChannel, targetUri, timeout);
    }

    @Override
    public void close() {
        try {
            if (parentChannel != null && parentChannel.isOpen()) parentChannel.close();
        } finally {
            eventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    /** Per-request consumer scoped to a single H2 stream on the shared parent. */
    static class StreamConsumer implements IPacketFinalizingConsumer<AggregatedRawResponse>, AutoCloseable {
        private final Channel parentChannel;
        private final URI targetUri;
        private final Duration timeout;
        private final List<ByteBuf> buffered = new ArrayList<>();
        private final AggregatedRawResponse.Builder responseBuilder;
        private final CompletableFuture<AggregatedRawResponse> responseFuture = new CompletableFuture<>();
        private Http2StreamChannel streamChannel;

        StreamConsumer(Channel parentChannel, URI targetUri, Duration timeout) {
            this.parentChannel = parentChannel;
            this.targetUri = targetUri;
            this.timeout = timeout;
            this.responseBuilder = AggregatedRawResponse.builder(Instant.now());
        }

        @Override
        public TrackedFuture<String, Void> consumeBytes(ByteBuf nextPacket) {
            buffered.add(nextPacket.retainedDuplicate());
            return TextTrackedFuture.completedFuture(null, () -> "buffered");
        }

        @Override
        public TrackedFuture<String, AggregatedRawResponse> finalizeRequest() {
            try {
                var parsed = parseBufferedH1();
                openStreamAndSend(parsed);
                var response = responseFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return TextTrackedFuture.completedFuture(response, () -> "H2 mux response");
            } catch (Throwable t) {
                responseBuilder.addErrorCause(t);
                return TextTrackedFuture.completedFuture(responseBuilder.build(), () -> "H2 mux error");
            }
        }

        private record Parsed(HttpRequest request, ByteBuf body) {}

        private Parsed parseBufferedH1() {
            var ec = new io.netty.channel.embedded.EmbeddedChannel(new HttpRequestDecoder());
            try {
                for (var buf : buffered) ec.writeInbound(buf.retainedDuplicate());
                HttpRequest req = null;
                var bodyBuf = Unpooled.buffer();
                Object next;
                while ((next = ec.readInbound()) != null) {
                    if (next instanceof HttpRequest r) req = r;
                    if (next instanceof HttpContent c) {
                        bodyBuf.writeBytes(c.content());
                        c.release();
                    }
                }
                if (req == null) throw new IllegalStateException("could not parse H1");
                return new Parsed(req, bodyBuf);
            } finally {
                ec.finishAndReleaseAll();
            }
        }

        private void openStreamAndSend(Parsed parsed) throws Exception {
            streamChannel = new Http2StreamChannelBootstrap(parentChannel)
                    .handler(new ChannelInitializer<Http2StreamChannel>() {
                        @Override
                        protected void initChannel(Http2StreamChannel ch) {
                            ch.pipeline().addLast(new ResponseCollector(responseBuilder, responseFuture));
                        }
                    })
                    .open()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);

            var headers = new DefaultHttp2Headers()
                    .method(parsed.request.method().name())
                    .path(parsed.request.uri())
                    .scheme("https")
                    .authority(parsed.request.headers().get("host", targetUri.getAuthority()));
            for (var h : parsed.request.headers()) {
                var name = h.getKey().toLowerCase(java.util.Locale.ROOT);
                if ("host".equals(name) || "connection".equals(name) || "keep-alive".equals(name)
                        || "transfer-encoding".equals(name) || "upgrade".equals(name)
                        || "proxy-connection".equals(name)) continue;
                headers.add(name, h.getValue());
            }
            boolean hasBody = parsed.body.readableBytes() > 0;
            streamChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, !hasBody)).sync();
            if (hasBody) {
                streamChannel.writeAndFlush(new DefaultHttp2DataFrame(
                        parsed.body.retainedDuplicate(), true)).sync();
            }
        }

        @Override
        public void close() {
            for (var b : buffered) b.release();
            buffered.clear();
            if (streamChannel != null && streamChannel.isOpen()) streamChannel.close();
        }
    }

    /** Per-stream response collector. Distinct from H2NettyPacketToHttpConsumer's nested
     *  collector so we don't depend on its internals. */
    static class ResponseCollector extends SimpleChannelInboundHandler<Http2Frame> {
        private final AggregatedRawResponse.Builder responseBuilder;
        private final CompletableFuture<AggregatedRawResponse> responseFuture;
        private final ByteBuf accumulatedBody = Unpooled.buffer();
        private DefaultHttpResponse h1Response;

        ResponseCollector(AggregatedRawResponse.Builder rb,
                          CompletableFuture<AggregatedRawResponse> rf) {
            this.responseBuilder = rb;
            this.responseFuture = rf;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Http2Frame frame) {
            if (frame instanceof Http2HeadersFrame hf) {
                if (h1Response == null) {
                    int statusCode = 500;
                    var statusValue = hf.headers().status();
                    if (statusValue != null) {
                        try { statusCode = Integer.parseInt(statusValue.toString()); }
                        catch (NumberFormatException ignored) {}
                    }
                    h1Response = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                            HttpResponseStatus.valueOf(statusCode));
                    for (var e : hf.headers()) {
                        var name = e.getKey().toString();
                        if (name.startsWith(":")) continue;
                        h1Response.headers().add(name, e.getValue().toString());
                    }
                    responseBuilder.addHttpParsedResponseObject(h1Response);
                }
                if (hf.isEndStream()) finalize0(ctx);
            } else if (frame instanceof Http2DataFrame df) {
                accumulatedBody.writeBytes(df.content().duplicate());
                // SimpleChannelInboundHandler auto-releases; do NOT call df.release() here.
                if (df.isEndStream()) finalize0(ctx);
            }
        }

        private void finalize0(ChannelHandlerContext ctx) {
            if (responseFuture.isDone()) return;
            var bodyBytes = new byte[accumulatedBody.readableBytes()];
            accumulatedBody.readBytes(bodyBytes);
            responseBuilder.addResponsePacket(bodyBytes);
            responseFuture.complete(responseBuilder.build());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            responseFuture.completeExceptionally(cause);
        }
    }
}
