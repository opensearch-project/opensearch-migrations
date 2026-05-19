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
import io.netty.channel.ChannelFuture;
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
 * RFC 0001 T6.2 — H2 target consumer.
 *
 * <p>Accepts H1-shape bytes via {@link #consumeBytes(ByteBuf)}, parses them locally to
 * extract the request line + headers + body, opens an {@link Http2StreamChannel} on a
 * shared parent connection toward the target, sends the request as HEADERS+DATA frames,
 * and returns the response on {@link #finalizeRequest()} as an
 * {@link AggregatedRawResponse} containing the status, headers, and body bytes.
 *
 * <p>The parent H2 connection (TLS+ALPN, Http2FrameCodec + Http2MultiplexHandler) is
 * established lazily on the first {@code consumeBytes} call. Subsequent requests on the
 * same consumer instance reuse the parent connection and open new stream sub-channels.
 *
 * <p>This is a lightweight implementation suitable for one logical request per consumer
 * instance — the {@link ConnectionReplaySession}-level multiplexing across many
 * consumers (T6.3) is the responsibility of the session orchestrator.
 */
@Slf4j
public class H2NettyPacketToHttpConsumer implements IPacketFinalizingConsumer<AggregatedRawResponse>, AutoCloseable {

    private final URI targetUri;
    private final boolean allowInsecure;
    private final Duration timeout;

    private final EventLoopGroup eventLoopGroup;
    private final List<ByteBuf> bufferedRequestBytes = new ArrayList<>();
    private final AggregatedRawResponse.Builder responseBuilder;

    private Channel parentChannel;
    private Http2StreamChannel streamChannel;
    private final CompletableFuture<AggregatedRawResponse> responseFuture = new CompletableFuture<>();

    public H2NettyPacketToHttpConsumer(URI targetUri, boolean allowInsecure, Duration timeout) {
        this.targetUri = targetUri;
        this.allowInsecure = allowInsecure;
        this.timeout = timeout;
        this.eventLoopGroup = new NioEventLoopGroup(1);
        this.responseBuilder = AggregatedRawResponse.builder(Instant.now());
    }

    @Override
    public TrackedFuture<String, Void> consumeBytes(ByteBuf nextPacket) {
        bufferedRequestBytes.add(nextPacket.retainedDuplicate());
        return TextTrackedFuture.completedFuture(null, () -> "buffered request bytes for H2 emission");
    }

    @Override
    public TrackedFuture<String, AggregatedRawResponse> finalizeRequest() {
        try {
            var parsed = parseBufferedH1Request();
            establishParentChannel().sync();
            sendH2Request(parsed);
            var response = responseFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return TextTrackedFuture.completedFuture(response, () -> "H2 response received");
        } catch (Throwable t) {
            log.atWarn().setCause(t).setMessage("H2 consumer error for {}").addArgument(targetUri).log();
            responseBuilder.addErrorCause(t);
            return TextTrackedFuture.completedFuture(responseBuilder.build(), () -> "H2 error response");
        }
    }

    @Override
    public void close() {
        try {
            if (streamChannel != null && streamChannel.isOpen()) streamChannel.close();
            if (parentChannel != null && parentChannel.isOpen()) parentChannel.close();
        } finally {
            eventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            for (var b : bufferedRequestBytes) b.release();
            bufferedRequestBytes.clear();
        }
    }

    /** Parse the buffered H1 bytes locally to extract method, path, headers, body. */
    private ParsedRequest parseBufferedH1Request() {
        var ec = new io.netty.channel.embedded.EmbeddedChannel(new HttpRequestDecoder());
        try {
            for (var buf : bufferedRequestBytes) {
                ec.writeInbound(buf.retainedDuplicate());
            }
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
            if (req == null) {
                throw new IllegalStateException("Could not parse buffered bytes as H1 request");
            }
            return new ParsedRequest(req, bodyBuf);
        } finally {
            ec.finishAndReleaseAll();
        }
    }

    /** Open one TLS+ALPN connection with H2 parent pipeline. */
    private ChannelFuture establishParentChannel() throws Exception {
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
            if (!f.isSuccess()) {
                handshakeFuture.completeExceptionally(f.cause());
            } else {
                this.parentChannel = f.channel();
            }
        });
        connectFuture.sync();
        handshakeFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return connectFuture;
    }

    /** Open a stream sub-channel and write the request as HEADERS + DATA. */
    private void sendH2Request(ParsedRequest parsed) throws Exception {
        var streamFuture = new Http2StreamChannelBootstrap(parentChannel)
                .handler(new ChannelInitializer<Http2StreamChannel>() {
                    @Override
                    protected void initChannel(Http2StreamChannel ch) {
                        ch.pipeline().addLast(new H2ResponseCollector(responseBuilder, responseFuture));
                    }
                })
                .open();
        this.streamChannel = streamFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);

        var h2Headers = new DefaultHttp2Headers()
                .method(parsed.request.method().name())
                .path(parsed.request.uri())
                .scheme("https")
                .authority(parsed.request.headers().get("host", targetUri.getAuthority()));
        // Forward regular headers (lower-cased) except Host (carried as :authority).
        for (var h : parsed.request.headers()) {
            var name = h.getKey().toLowerCase(java.util.Locale.ROOT);
            if ("host".equals(name) || "connection".equals(name) || "keep-alive".equals(name)
                    || "transfer-encoding".equals(name) || "upgrade".equals(name) || "proxy-connection".equals(name)) {
                continue;
            }
            h2Headers.add(name, h.getValue());
        }

        boolean hasBody = parsed.body.readableBytes() > 0;
        streamChannel.writeAndFlush(new DefaultHttp2HeadersFrame(h2Headers, !hasBody)).sync();
        if (hasBody) {
            streamChannel.writeAndFlush(new DefaultHttp2DataFrame(
                    parsed.body.retainedDuplicate(), true)).sync();
        }
    }

    /** Per-stream handler on the client side: collects HEADERS + DATA into the response builder. */
    static class H2ResponseCollector extends SimpleChannelInboundHandler<Http2Frame> {
        private final AggregatedRawResponse.Builder responseBuilder;
        private final CompletableFuture<AggregatedRawResponse> responseFuture;
        private DefaultHttpResponse h1Response;
        private final ByteBuf accumulatedBody = Unpooled.buffer();

        H2ResponseCollector(AggregatedRawResponse.Builder rb,
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
                    for (var entry : hf.headers()) {
                        var name = entry.getKey().toString();
                        if (name.startsWith(":")) continue;
                        h1Response.headers().add(name, entry.getValue().toString());
                    }
                    responseBuilder.addHttpParsedResponseObject(h1Response);
                }
                if (hf.isEndStream()) {
                    finalizeResponse(ctx);
                }
            } else if (frame instanceof Http2DataFrame df) {
                var content = df.content();
                accumulatedBody.writeBytes(content.duplicate());
                // SimpleChannelInboundHandler auto-releases; do NOT call df.release() here.
                if (df.isEndStream()) {
                    finalizeResponse(ctx);
                }
            }
        }

        private void finalizeResponse(ChannelHandlerContext ctx) {
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

    private record ParsedRequest(HttpRequest request, ByteBuf body) {}
}
