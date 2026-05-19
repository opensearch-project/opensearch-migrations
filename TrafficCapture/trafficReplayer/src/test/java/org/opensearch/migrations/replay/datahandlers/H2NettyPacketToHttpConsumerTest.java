package org.opensearch.migrations.replay.datahandlers;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.opensearch.migrations.replay.AggregatedRawResponse;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
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
 * RFC 0001 T6.2 — coverage for {@link H2NettyPacketToHttpConsumer}.
 *
 * <p>TDD scope: the consumer accepts H1-shape bytes via {@code consumeBytes}, opens an
 * H2 stream toward a target H2 endpoint, sends the request as HEADERS+DATA frames, and
 * returns the response as {@link AggregatedRawResponse}.
 *
 * <p>Tests boot an in-process Netty H2 echo server (TLS+ALPN, self-signed cert) and
 * drive the consumer against it.
 */
class H2NettyPacketToHttpConsumerTest {

    private EventLoopGroup serverBoss;
    private EventLoopGroup serverWorker;
    private Channel serverChannel;

    @AfterEach
    void shutdownServer() throws Exception {
        if (serverChannel != null) serverChannel.close().sync();
        if (serverWorker != null) serverWorker.shutdownGracefully().sync();
        if (serverBoss != null) serverBoss.shutdownGracefully().sync();
    }

    /**
     * Boot a TLS+ALPN H2 server that echoes a 200 with body "ok-h2-response" on every stream.
     * Returns the bound port.
     */
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

    /** Per-stream handler on the server side: replies 200 with a fixed body once headers arrive. */
    static class EchoH2StreamHandler extends io.netty.channel.SimpleChannelInboundHandler<Http2Frame> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Http2Frame frame) {
            if (frame instanceof Http2HeadersFrame hf) {
                var respHeaders = new DefaultHttp2Headers().status("200")
                        .add("content-type", "text/plain");
                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(respHeaders, false));
                ctx.writeAndFlush(new DefaultHttp2DataFrame(
                        Unpooled.copiedBuffer("ok-h2-response", StandardCharsets.UTF_8),
                        true));
            } else if (frame instanceof Http2DataFrame df) {
                df.release();
            }
        }
    }

    @Test
    @Timeout(20)
    void postRequest_throughH2Consumer_receives200Response() throws Exception {
        int port = bootH2EchoServer();
        var targetUri = URI.create("https://127.0.0.1:" + port);

        var consumer = new H2NettyPacketToHttpConsumer(
                targetUri, /*allowInsecure*/ true, Duration.ofSeconds(5));
        try {
            // Send a minimal H1-shape POST as bytes.
            var requestBytes = ("POST /_bulk HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Content-Type: application/x-ndjson\r\n"
                    + "Content-Length: 16\r\n"
                    + "\r\n"
                    + "{\"index\":{}}\n{}\n").getBytes(StandardCharsets.UTF_8);
            consumer.consumeBytes(Unpooled.wrappedBuffer(requestBytes)).get();
            var response = consumer.finalizeRequest().get();

            Assertions.assertNotNull(response, "response must not be null");
            Assertions.assertNotNull(response.getRawResponse(),
                    "rawResponse (HttpResponse) must be set");
            Assertions.assertEquals(200, response.getRawResponse().status().code(),
                    "echo server returns 200");
            // Body must contain our fixed echo body.
            var bodyBuf = response.getResponseAsByteBuf();
            var bodyStr = bodyBuf.toString(StandardCharsets.UTF_8);
            Assertions.assertTrue(bodyStr.contains("ok-h2-response"),
                    "response body must contain echo string, was: " + bodyStr);
        } finally {
            consumer.close();
        }
    }
}
