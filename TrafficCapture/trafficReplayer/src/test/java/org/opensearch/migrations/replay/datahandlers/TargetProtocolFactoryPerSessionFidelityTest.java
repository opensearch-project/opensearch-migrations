package org.opensearch.migrations.replay.datahandlers;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
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
 * Verifies per-source-connection fidelity on the replayer's H2 target side.
 *
 * <p>If the source captured N distinct H2 connections (each with its own set of
 * multiplexed streams), the replayer must open N distinct H2 parent connections to
 * the target — not collapse all source connections into a single shared parent.
 * Within each session, requests still multiplex on the session's own parent.
 *
 * <p>This is a fidelity guarantee: connection topology on the wire must match the
 * source. Aggregating distinct source connections into one target connection
 * misrepresents how the cluster was hit.
 */
class TargetProtocolFactoryPerSessionFidelityTest {

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
            if (frame instanceof Http2HeadersFrame hf && hf.isEndStream()) respond(ctx);
            else if (frame instanceof Http2DataFrame df && df.isEndStream()) respond(ctx);
        }
        private void respond(ChannelHandlerContext ctx) {
            ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), false));
            ctx.writeAndFlush(new DefaultHttp2DataFrame(
                    Unpooled.copiedBuffer("ok", StandardCharsets.UTF_8), true));
        }
    }

    @Test
    @Timeout(30)
    void distinctSourceSessions_yieldDistinctTargetParentConnections() throws Exception {
        int port = bootH2EchoServer();
        var targetUri = URI.create("https://127.0.0.1:" + port);

        var factory = TargetProtocolFactory.forTarget(targetUri,
                /*targetEnableHttp2*/ true, /*allowInsecure*/ true, Duration.ofSeconds(10));
        try {
            int sessionCount = 5;
            int requestsPerSession = 3;
            var counter = new AtomicInteger();
            var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Void>>();

            for (int s = 0; s < sessionCount; s++) {
                int sessionIdx = s;
                String sessionId = "src-session-" + s;
                for (int r = 0; r < requestsPerSession; r++) {
                    int reqIdx = r;
                    futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                        // The factory needs to know which session this request belongs to so it can
                        // mint distinct parent connections per source session.
                        var consumer = factory.createForSession(targetUri, sessionId, null, null,
                                Duration.ofSeconds(10));
                        try {
                            var bytes = ("GET /s" + sessionIdx + "/r" + reqIdx + " HTTP/1.1\r\n"
                                    + "Host: 127.0.0.1\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                            consumer.consumeBytes(Unpooled.wrappedBuffer(bytes)).get();
                            consumer.finalizeRequest().get();
                            counter.incrementAndGet();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        } finally {
                            if (consumer instanceof AutoCloseable ac) {
                                try { ac.close(); } catch (Exception ignored) {}
                            }
                        }
                    }));
                }
            }
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .get(20, java.util.concurrent.TimeUnit.SECONDS);

            Assertions.assertEquals(sessionCount * requestsPerSession, counter.get(),
                    "all requests must complete");

            // Fidelity assertion:
            //   Distinct TCP channels at the server side = sessionCount + 1 (the +1 is the one-shot
            //   ALPN probe done by forTarget()). NOT 1 (broken multiplexing-collapse) and NOT
            //   sessionCount * requestsPerSession (broken no-multiplexing).
            int observed = distinctClientConnections.size();
            int expected = sessionCount + 1;
            Assertions.assertEquals(expected, observed,
                    "expected " + expected + " distinct target connections (1 ALPN probe + " + sessionCount
                            + " per-session parents), observed " + observed
                            + ". This is a fidelity guarantee: distinct source connections must map to "
                            + "distinct target connections. Multiplexing happens only WITHIN a session.");
        } finally {
            factory.close();
        }
    }

    @Test
    @Timeout(30)
    void sameSourceSession_reusesParentConnection() throws Exception {
        int port = bootH2EchoServer();
        var targetUri = URI.create("https://127.0.0.1:" + port);

        var factory = TargetProtocolFactory.forTarget(targetUri,
                true, true, Duration.ofSeconds(10));
        try {
            String sessionId = "shared-session";
            int n = 8;
            var counter = new AtomicInteger();
            var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Void>>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                    var consumer = factory.createForSession(targetUri, sessionId, null, null,
                            Duration.ofSeconds(10));
                    try {
                        var bytes = ("GET /shared/" + idx + " HTTP/1.1\r\n"
                                + "Host: 127.0.0.1\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                        consumer.consumeBytes(Unpooled.wrappedBuffer(bytes)).get();
                        consumer.finalizeRequest().get();
                        counter.incrementAndGet();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        if (consumer instanceof AutoCloseable ac) {
                            try { ac.close(); } catch (Exception ignored) {}
                        }
                    }
                }));
            }
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .get(20, java.util.concurrent.TimeUnit.SECONDS);

            Assertions.assertEquals(n, counter.get());
            // Same session → same parent. Server sees 2 distinct channels: probe + 1 session parent.
            Assertions.assertEquals(2, distinctClientConnections.size(),
                    "all requests on the same session must share one parent (multiplexed); "
                            + "probe + 1 parent = 2 distinct connections");
        } finally {
            factory.close();
        }
    }
}
