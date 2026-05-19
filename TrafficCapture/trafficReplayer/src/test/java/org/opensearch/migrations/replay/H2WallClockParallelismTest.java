package org.opensearch.migrations.replay;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.datahandlers.TargetProtocolFactory;

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
 * End-to-end test of the H2 wall-clock parallelism through TargetProtocolFactory.
 *
 * <p>Boots an H2 echo server that holds each request for {@code DELAY_PER_REQUEST_MS} before
 * responding. Issues {@code N} concurrent requests via {@code factory.create()} and asserts
 * the total elapsed wall-clock time is closer to one delay than to N delays — i.e. the
 * requests truly multiplexed and ran in parallel rather than serializing.
 *
 * <p>This is the test that previously would have FAILED before the orchestrator refactor:
 * the per-session schedule chained tasks one after another. After the refactor, multiplexed
 * sessions fire each task at its own atTime in parallel.
 */
class H2WallClockParallelismTest {

    private static final int N = 5;
    private static final long DELAY_PER_REQUEST_MS = 500;
    /** Total time should be roughly one delay (parallel) plus overhead, NOT N×delay (serial). */
    private static final long PARALLEL_BUDGET_MS = (long) (DELAY_PER_REQUEST_MS * 2.5);

    private EventLoopGroup serverBoss;
    private EventLoopGroup serverWorker;
    private Channel serverChannel;

    @AfterEach
    void shutdown() throws Exception {
        if (serverChannel != null) serverChannel.close().sync();
        if (serverWorker != null) serverWorker.shutdownGracefully().sync();
        if (serverBoss != null) serverBoss.shutdownGracefully().sync();
    }

    private int bootDelayingH2Server() throws Exception {
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
        // Plenty of worker threads so server can hold N requests concurrently.
        serverWorker = new NioEventLoopGroup(N);
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
                                                    sub.pipeline().addLast(new DelayingEcho());
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

    /** Per-stream handler that schedules the response after a delay. */
    static class DelayingEcho extends SimpleChannelInboundHandler<Http2Frame> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Http2Frame frame) {
            if (frame instanceof Http2HeadersFrame hf && hf.isEndStream()) {
                schedule(ctx);
            } else if (frame instanceof Http2DataFrame df && df.isEndStream()) {
                schedule(ctx);
            }
        }
        private void schedule(ChannelHandlerContext ctx) {
            ctx.executor().schedule(() -> {
                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(
                        new DefaultHttp2Headers().status("200"), false));
                ctx.writeAndFlush(new DefaultHttp2DataFrame(
                        Unpooled.copiedBuffer("delayed", StandardCharsets.UTF_8), true));
            }, DELAY_PER_REQUEST_MS, TimeUnit.MILLISECONDS);
        }
    }

    @Test
    @Timeout(30)
    void multiplexedSession_runsRequestsInParallel_notSerially() throws Exception {
        int port = bootDelayingH2Server();
        var targetUri = URI.create("https://127.0.0.1:" + port);

        var factory = TargetProtocolFactory.forTarget(targetUri,
                /*targetEnableHttp2*/ true, /*allowInsecure*/ true, Duration.ofSeconds(15));
        try {
            var futures = new ArrayList<CompletableFuture<String>>();
            var counter = new AtomicInteger();
            long startNanos = System.nanoTime();

            for (int i = 0; i < N; i++) {
                int idx = i;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    var consumer = factory.create(targetUri, null, null, Duration.ofSeconds(10));
                    try {
                        var bytes = ("GET /req" + idx + " HTTP/1.1\r\n"
                                + "Host: 127.0.0.1\r\n\r\n").getBytes(StandardCharsets.UTF_8);
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
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            Assertions.assertEquals(N, counter.get(), "all requests must complete");
            // Wall-clock assertion: parallel execution should be FAR less than N×delay.
            Assertions.assertTrue(elapsedMs < PARALLEL_BUDGET_MS,
                    "expected parallel execution under " + PARALLEL_BUDGET_MS + "ms, was " + elapsedMs
                            + "ms (serial baseline: " + (DELAY_PER_REQUEST_MS * N) + "ms)");
        } finally {
            factory.close();
        }
    }
}
