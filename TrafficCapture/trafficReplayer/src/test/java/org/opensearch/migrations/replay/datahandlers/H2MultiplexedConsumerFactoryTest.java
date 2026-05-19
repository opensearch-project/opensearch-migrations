package org.opensearch.migrations.replay.datahandlers;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
import io.netty.handler.codec.http2.Http2FrameCodec;
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
 * — verifies H2MultiplexedConsumerFactory issues N concurrent requests
 * on a single shared parent H2 connection, with each request opening its own
 * stream sub-channel. Each request must complete independently.
 */
class H2MultiplexedConsumerFactoryTest {

    private EventLoopGroup serverBoss;
    private EventLoopGroup serverWorker;
    private Channel serverChannel;

    @AfterEach
    void shutdownServer() throws Exception {
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
                        var engine = ctx.newEngine(ch.alloc());
                        ch.pipeline().addLast(new SslHandler(engine));
                        ch.pipeline().addLast(new io.netty.handler.ssl.ApplicationProtocolNegotiationHandler(
                                ApplicationProtocolNames.HTTP_1_1) {
                            @Override
                            protected void configurePipeline(ChannelHandlerContext c, String protocol) {
                                if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                                    Http2FrameCodec codec = Http2FrameCodecBuilder.forServer().build();
                                    c.pipeline().addLast(codec);
                                    c.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                                        @Override
                                        protected void initChannel(Channel sub) {
                                            sub.pipeline().addLast(new EchoH2StreamHandler());
                                        }
                                    }));
                                } else {
                                    c.close();
                                }
                            }
                        });
                    }
                });
        serverChannel = b.bind("127.0.0.1", 0).sync().channel();
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    static class EchoH2StreamHandler extends SimpleChannelInboundHandler<Http2Frame> {
        private DefaultHttp2Headers reqHeaders;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Http2Frame frame) {
            if (frame instanceof Http2HeadersFrame hf) {
                reqHeaders = (DefaultHttp2Headers) hf.headers();
                if (hf.isEndStream()) {
                    sendResponse(ctx, "echo-no-body");
                }
            } else if (frame instanceof Http2DataFrame df) {
                var body = df.content().toString(StandardCharsets.UTF_8);
                if (df.isEndStream()) {
                    sendResponse(ctx, "echo-" + body);
                }
            }
        }

        private void sendResponse(ChannelHandlerContext ctx, String body) {
            var respHeaders = new DefaultHttp2Headers().status("200")
                    .add("content-type", "text/plain");
            ctx.writeAndFlush(new DefaultHttp2HeadersFrame(respHeaders, false));
            ctx.writeAndFlush(new DefaultHttp2DataFrame(
                    Unpooled.copiedBuffer(body, StandardCharsets.UTF_8), true));
        }
    }

    @Test
    @Timeout(30)
    void tenConcurrentRequests_completeIndependently() throws Exception {
        int port = bootH2EchoServer();
        var targetUri = URI.create("https://127.0.0.1:" + port);
        var factory = new H2MultiplexedConsumerFactory(targetUri,
                /*allowInsecure*/ true, Duration.ofSeconds(15));
        try {
            factory.open();

            int n = 10;
            var futures = new ArrayList<CompletableFuture<String>>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                var consumer = factory.createConsumer();
                var requestBytes = ("POST /req" + idx + " HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\n"
                        + "Content-Type: text/plain\r\n"
                        + "Content-Length: " + ("body" + idx).length() + "\r\n"
                        + "\r\n"
                        + "body" + idx).getBytes(StandardCharsets.UTF_8);
                var f = CompletableFuture.supplyAsync(() -> {
                    try {
                        consumer.consumeBytes(Unpooled.wrappedBuffer(requestBytes)).get();
                        var response = consumer.finalizeRequest().get();
                        return response.getResponseAsByteBuf().toString(StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        if (consumer instanceof AutoCloseable ac) {
                            try { ac.close(); } catch (Exception ignored) {}
                        }
                    }
                });
                futures.add(f);
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(20, TimeUnit.SECONDS);

            for (int i = 0; i < n; i++) {
                var body = futures.get(i).get();
                Assertions.assertTrue(body.contains("echo-body" + i),
                        "request " + i + " body should round-trip: " + body);
            }
        } finally {
            factory.close();
        }
    }
}
