package org.opensearch.migrations.trafficcapture.proxyserver;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
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
 * RFC 0001 T3.2 — startup ALPN probe of the upstream.
 *
 * <p>The probe opens one TLS connection to the upstream, observes the ALPN protocol
 * the upstream selects, then closes. The result is used by the proxy startup path:
 * if {@code --enableHttp2} was set but upstream doesn't speak h2, the proxy
 * downgrades to advertising http/1.1-only to clients.
 *
 * <p>Tests in this class boot a small in-process TLS server with a configurable
 * ALPN advertisement and assert the probe returns the expected protocol.
 */
public class UpstreamAlpnProbeTest {

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    @AfterEach
    void shutdown() throws Exception {
        if (serverChannel != null) serverChannel.close().sync();
        if (workerGroup != null) workerGroup.shutdownGracefully().sync();
        if (bossGroup != null) bossGroup.shutdownGracefully().sync();
    }

    /** Boot a TLS server that advertises the given ALPN protocols. Returns the bound port. */
    private int bootServer(List<String> advertisedAlpn) throws Exception {
        var ssc = new SelfSignedCertificate();
        var ctxBuilder = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey());
        if (!advertisedAlpn.isEmpty()) {
            ctxBuilder.applicationProtocolConfig(new ApplicationProtocolConfig(
                    ApplicationProtocolConfig.Protocol.ALPN,
                    ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                    ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                    advertisedAlpn));
        }
        SslContext ctx = ctxBuilder.build();

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(1);
        var b = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        var engine = ctx.newEngine(ch.alloc());
                        ch.pipeline().addLast(new SslHandler(engine));
                        // No further handlers — peer just sees the handshake complete.
                    }
                });
        serverChannel = b.bind("127.0.0.1", 0).sync().channel();
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    @Test
    @Timeout(15)
    void upstreamSpeaksH2_probeReturnsH2() throws Exception {
        int port = bootServer(List.of(ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1));
        var uri = URI.create("https://127.0.0.1:" + port);

        var result = UpstreamAlpnProbe.probe(uri, /*allowInsecure*/ true, Duration.ofSeconds(5));
        Assertions.assertEquals(ApplicationProtocolNames.HTTP_2, result,
                "upstream advertising [h2,http/1.1] should select h2");
    }

    @Test
    @Timeout(15)
    void upstreamH1Only_probeReturnsH1() throws Exception {
        int port = bootServer(List.of(ApplicationProtocolNames.HTTP_1_1));
        var uri = URI.create("https://127.0.0.1:" + port);

        var result = UpstreamAlpnProbe.probe(uri, true, Duration.ofSeconds(5));
        Assertions.assertEquals(ApplicationProtocolNames.HTTP_1_1, result,
                "upstream advertising only http/1.1 should select http/1.1");
    }

    @Test
    @Timeout(15)
    void upstreamNoAlpn_probeReturnsEmptyOrH1() throws Exception {
        int port = bootServer(List.of()); // No ALPN at all
        var uri = URI.create("https://127.0.0.1:" + port);

        var result = UpstreamAlpnProbe.probe(uri, true, Duration.ofSeconds(5));
        Assertions.assertTrue(result == null || result.isEmpty()
                || result.equals(ApplicationProtocolNames.HTTP_1_1),
                "upstream with no ALPN config should yield null/empty or http/1.1, was: " + result);
    }

    @Test
    @Timeout(15)
    void unreachableUpstream_throws() {
        // Use a port that's almost certainly closed.
        var uri = URI.create("https://127.0.0.1:1");
        Assertions.assertThrows(Exception.class,
                () -> UpstreamAlpnProbe.probe(uri, true, Duration.ofSeconds(2)),
                "unreachable upstream must surface an exception, not silently return null");
    }

    /**
     * Convenience helper used by the proxy startup logic:
     * given a probe result, decide what protocols to advertise to clients.
     */
    @Test
    void decideClientAdvertisement_h2Available() {
        var advertise = UpstreamAlpnProbe.decideClientAdvertisement(
                /*proxyWantsH2*/ true, /*upstreamProtocol*/ "h2");
        Assertions.assertEquals(List.of(ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1),
                advertise);
    }

    @Test
    void decideClientAdvertisement_h2WantedButUpstreamH1_downgrades() {
        var advertise = UpstreamAlpnProbe.decideClientAdvertisement(true, "http/1.1");
        Assertions.assertEquals(List.of(ApplicationProtocolNames.HTTP_1_1), advertise,
                "proxy must downgrade to h1-only when upstream doesn't speak h2");
    }

    @Test
    void decideClientAdvertisement_proxyDoesNotWantH2() {
        var advertise = UpstreamAlpnProbe.decideClientAdvertisement(false, "h2");
        Assertions.assertTrue(advertise.isEmpty(),
                "proxy not asking for h2 means no ALPN advertisement (legacy behavior)");
    }
}
