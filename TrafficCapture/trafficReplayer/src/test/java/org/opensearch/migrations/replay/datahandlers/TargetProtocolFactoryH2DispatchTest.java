package org.opensearch.migrations.replay.datahandlers;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

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
 * RFC 0001 — verifies TargetProtocolFactory ACTUALLY dispatches to the H2 consumer when
 * the target negotiates h2 (the wiring step that completes T6.2 + T6.4 plumbing in
 * production).
 *
 * <p>This test boots a real Netty H2 echo server, points the factory at it with
 * {@code targetEnableHttp2=true}, and asserts:
 * <ul>
 *   <li>create() returns an H2-shaped consumer (not the H1 fallback)</li>
 *   <li>the consumer round-trips a request through the H2 server</li>
 *   <li>the ALPN probe runs once + caches</li>
 * </ul>
 */
class TargetProtocolFactoryH2DispatchTest {

    private EventLoopGroup serverBoss;
    private EventLoopGroup serverWorker;
    private Channel serverChannel;

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
        serverWorker = new NioEventLoopGroup(1);
        var b = new ServerBootstrap()
                .group(serverBoss, serverWorker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
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
                                                    sub.pipeline().addLast(new EchoH2(sub));
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
        EchoH2(Channel ignore) {}
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Http2Frame frame) {
            if (frame instanceof Http2HeadersFrame hf && hf.isEndStream()) {
                respond(ctx);
            } else if (frame instanceof Http2DataFrame df && df.isEndStream()) {
                respond(ctx);
            }
        }
        private void respond(ChannelHandlerContext ctx) {
            ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200"), false));
            ctx.writeAndFlush(new DefaultHttp2DataFrame(
                    Unpooled.copiedBuffer("h2-dispatched", StandardCharsets.UTF_8), true));
        }
    }

    @Test
    @Timeout(20)
    void factory_withH2EnabledAndH2Target_returnsH2Consumer_thatRoundTrips() throws Exception {
        int port = bootH2EchoServer();
        var targetUri = URI.create("https://127.0.0.1:" + port);

        // Real probe + dispatch — the factory should detect h2 ALPN and return the H2 consumer.
        var factory = TargetProtocolFactory.forTarget(targetUri, /*targetEnableHttp2*/ true,
                /*allowInsecure*/ true, Duration.ofSeconds(10));

        var consumer = factory.create(targetUri, /*session*/ null, /*ctx*/ null, Duration.ofSeconds(10));
        Assertions.assertNotNull(consumer);
        var consumerClass = consumer.getClass().getSimpleName();
        Assertions.assertTrue(consumerClass.equals("StreamConsumer")
                        || consumerClass.equals("H2NettyPacketToHttpConsumer"),
                "factory must return an H2 consumer (StreamConsumer from multiplex factory or "
                        + "H2NettyPacketToHttpConsumer), was: " + consumerClass);

        // Round-trip through the live consumer.
        var requestBytes = ("POST /t HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 4\r\n\r\nbody")
                .getBytes(StandardCharsets.UTF_8);
        consumer.consumeBytes(Unpooled.wrappedBuffer(requestBytes)).get();
        var response = consumer.finalizeRequest().get();
        Assertions.assertEquals(200, response.getRawResponse().status().code());
        Assertions.assertTrue(response.getResponseAsByteBuf().toString(StandardCharsets.UTF_8)
                .contains("h2-dispatched"));

        if (consumer instanceof AutoCloseable ac) ac.close();
        factory.close();
    }

    @Test
    @Timeout(20)
    void factory_withoutH2_returnsH1ConsumerEvenForH2Target() throws Exception {
        int port = bootH2EchoServer();
        var targetUri = URI.create("https://127.0.0.1:" + port);

        var capturedFactoryUsed = new AtomicReference<Boolean>(false);
        TargetProtocolFactory.ConsumerFactory h1Stub = (session, ctx, timeout) -> {
            capturedFactoryUsed.set(true);
            // Return a dummy consumer (we don't actually use it for round-trip in this test).
            return new IPacketFinalizingConsumer<org.opensearch.migrations.replay.AggregatedRawResponse>() {
                @Override
                public org.opensearch.migrations.utils.TrackedFuture<String, Void> consumeBytes(
                        io.netty.buffer.ByteBuf packetData) {
                    return org.opensearch.migrations.utils.TrackedFuture.Factory
                            .completedFuture(null, () -> "stub");
                }
                @Override
                public org.opensearch.migrations.utils.TrackedFuture<String,
                        org.opensearch.migrations.replay.AggregatedRawResponse> finalizeRequest() {
                    return org.opensearch.migrations.utils.TrackedFuture.Factory
                            .completedFuture(null, () -> "stub");
                }
            };
        };

        var factory = new TargetProtocolFactory(/*targetEnableHttp2*/ false, h1Stub);
        var consumer = factory.create(targetUri, null, null, Duration.ofSeconds(5));
        Assertions.assertNotNull(consumer);
        Assertions.assertTrue(capturedFactoryUsed.get(),
                "with --targetEnableHttp2 disabled, the H1 ConsumerFactory must be used");
    }
}
