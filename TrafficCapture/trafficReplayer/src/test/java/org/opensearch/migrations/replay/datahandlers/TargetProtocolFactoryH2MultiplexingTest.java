package org.opensearch.migrations.replay.datahandlers;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Frame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies that multiple H2 requests to the same target authority share ONE parent
 * H2 connection — i.e. the multiplex factory cache works end-to-end through
 * {@link TargetProtocolFactory#create}.
 *
 * <p>Strategy: boot a Netty H2 echo server that counts the number of distinct
 * client TCP channels it accepts. Issue 10 consumer-mints + round-trips through
 * {@code TargetProtocolFactory.create()}. Assert: only ONE TCP channel was
 * established at the server side. That proves all 10 H2 requests multiplexed
 * onto a single connection.
 */
class TargetProtocolFactoryH2MultiplexingTest {

    private EventLoopGroup serverBoss;
    private EventLoopGroup serverWorker;
    private Channel serverChannel;
    private final Set<Channel> distinctClientConnections = java.util.Collections.synchronizedSet(new HashSet<>());

    @AfterEach
    void shutdown() throws Exception {
        if (serverChannel != null) serverChannel.close().sync();
        if (serverWorker != null) serverWorker.shutdownGracefully().sync();
        if (serverBoss != null) serverBoss.shutdownGracefully().sync();
    }

    private int bootH2EchoServer() throws Exception {
        var ssc = new SelfSignedCertificate();
        SslContext ctx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2,
                        ApplicationProtocolNames.HTTP_1_1))
                .build();
        serverBoss = new NioEventLoopGroup(1);
        serverWorker = new NioEventLoopGroup(2);
        var b = new ServerBootstrap()
                .group(serverBoss, serverWorker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // Track each new TCP connection — this is the load-bearing assertion.
                        distinctClientConnections.add(ch);
                        var engine = ctx.newEngine(ch.alloc());
                        ch.pipeline().addLast(new SslHandler(engine));
                        ch.pipeline().addLast(new io.netty.handler.ssl.ApplicationProtocolNegotiationHandler(
                                ApplicationProtocolNames.HTTP_1_1) {
                            @Override
                            protected void configurePipeline(ChannelHandlerContext c, String protocol) {
                                if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                                    c.pipeline().addLast(Http2FrameCodecBuilder.forServer().build());
                                    c.pipeline().addLast(new Http2MultiplexHandler(
                                            new ChannelInitializer<Channel>() {
                                                @Override
                                                protected void initChannel(Channel sub) {
                                                    sub.pipeline().addLast(new EchoH2());
                                                }
                                            }));
                                }
                            }
                        });
                    }
                });
        serverChannel = b.bind("127.0.0.1", 0).sync().channel();
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    static class EchoH2 extends SimpleChannelInboundHandler<Http2Frame> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Http2Frame frame) {
            if (frame instanceof Http2HeadersFrame hf && hf.isEndStream()) {
                respond(ctx, "no-body");
            } else if (frame instanceof Http2DataFrame df && df.isEndStream()) {
                respond(ctx, df.content().toString(StandardCharsets.UTF_8));
            }
        }
        private void respond(ChannelHandlerContext ctx, String marker) {
            var headers = new DefaultHttp2Headers().status("200");
            ctx.writeAndFlush(new DefaultHttp2HeadersFrame(headers, false));
            ctx.writeAndFlush(new DefaultHttp2DataFrame(
                    Unpooled.copiedBuffer("ok-" + marker, StandardCharsets.UTF_8), true));
        }
    }

    @Test
    @Timeout(30)
    void tenH2RequestsThroughFactory_shareSingleParentConnection() throws Exception {
        int port = bootH2EchoServer();
        var targetUri = URI.create("https://127.0.0.1:" + port);

        var factory = TargetProtocolFactory.forTarget(targetUri,
                /*targetEnableHttp2*/ true, /*allowInsecure*/ true, Duration.ofSeconds(10));
        try {
            int n = 10;
            var futures = new ArrayList<CompletableFuture<String>>();
            var counter = new AtomicInteger();
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    var consumer = factory.create(targetUri, null, null, Duration.ofSeconds(10));
                    try {
                        var bytes = ("POST /req" + idx + " HTTP/1.1\r\n"
                                + "Host: 127.0.0.1\r\n"
                                + "Content-Length: " + ("body" + idx).length() + "\r\n"
                                + "\r\n"
                                + "body" + idx).getBytes(StandardCharsets.UTF_8);
                        consumer.consumeBytes(Unpooled.wrappedBuffer(bytes)).get();
                        var resp = consumer.finalizeRequest().get();
                        counter.incrementAndGet();
                        return resp.getResponseAsByteBuf().toString(StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        if (consumer instanceof AutoCloseable ac) {
                            try { ac.close(); } catch (Exception ignored) {}
                        }
                    }
                }));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(20, TimeUnit.SECONDS);

            // Verify all 10 succeeded.
            Assertions.assertEquals(n, counter.get(), "all 10 H2 requests must complete");
            for (int i = 0; i < n; i++) {
                var body = futures.get(i).get();
                Assertions.assertTrue(body.contains("ok-body" + i),
                        "request " + i + " response body must echo: " + body);
            }

            // The load-bearing assertion: at most TWO TCP connections at the server side.
            // - 1 for the one-shot ALPN probe (opened by forTarget on factory creation, then closed)
            // - 1 long-lived multiplex parent shared by all 10 stream consumers
            // If we saw 11 (1 probe + 10 per-request), multiplexing would be broken.
            Assertions.assertTrue(distinctClientConnections.size() <= 2,
                    "H2 requests must share a parent TCP connection (multiplexing); observed "
                            + distinctClientConnections.size() + " distinct client channels (expected ≤2: "
                            + "one-shot ALPN probe + one shared multiplex parent)");
        } finally {
            factory.close();
        }
    }
}
