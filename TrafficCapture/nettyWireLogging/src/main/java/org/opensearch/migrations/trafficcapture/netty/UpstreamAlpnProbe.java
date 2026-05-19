package org.opensearch.migrations.trafficcapture.netty;

import javax.net.ssl.SSLEngine;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * — startup ALPN probe of the upstream.
 *
 * <p>{@link #probe(URI, boolean, Duration)} opens one TLS connection to the upstream
 * advertising both {@code h2} and {@code http/1.1} via ALPN, observes which protocol
 * the upstream selects, and returns it. The connection is closed immediately after.
 *
 * <p>{@link #decideClientAdvertisement(boolean, String)} translates the probe result
 * into the protocol list the proxy should advertise to its clients: when the upstream
 * doesn't speak h2, the proxy downgrades to {@code [http/1.1]} only — preventing the
 * "client picks h2 but upstream rejects" failure mode at handshake time.
 */
@Slf4j
public final class UpstreamAlpnProbe {

    private UpstreamAlpnProbe() {}

    /**
     * Open one TLS connection to {@code targetUri}, observe the negotiated ALPN, return.
     *
     * @param targetUri          full upstream URI (must be https)
     * @param allowInsecure      when true, trust any cert (for testing / self-signed upstreams)
     * @param timeout            max wait for handshake completion
     * @return the negotiated ALPN protocol string ({@code "h2"} or {@code "http/1.1"});
     *         possibly empty/null when the upstream did not select an ALPN protocol
     * @throws Exception when the connection cannot be established or the handshake fails
     */
    public static String probe(URI targetUri, boolean allowInsecure, Duration timeout) throws Exception {
        if (!"https".equalsIgnoreCase(targetUri.getScheme())) {
            throw new IllegalArgumentException(
                    "ALPN probe only meaningful over TLS; got scheme=" + targetUri.getScheme());
        }
        var ctxBuilder = SslContextBuilder.forClient()
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2,
                        ApplicationProtocolNames.HTTP_1_1));
        if (allowInsecure) ctxBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
        SslContext sslCtx = ctxBuilder.build();

        EventLoopGroup group = new NioEventLoopGroup(1);
        var resultFuture = new CompletableFuture<String>();
        try {
            var bootstrap = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            SSLEngine engine = sslCtx.newEngine(ch.alloc());
                            engine.setUseClientMode(true);
                            var sslHandler = new SslHandler(engine);
                            ch.pipeline().addLast(sslHandler);
                            sslHandler.handshakeFuture().addListener(f -> {
                                if (f.isSuccess()) {
                                    var negotiated = sslHandler.applicationProtocol();
                                    resultFuture.complete(negotiated == null ? "" : negotiated);
                                } else {
                                    resultFuture.completeExceptionally(f.cause());
                                }
                                ch.close();
                            });
                        }
                    });

            int port = targetUri.getPort() < 0 ? 443 : targetUri.getPort();
            var connectFuture = bootstrap.connect(targetUri.getHost(), port);
            connectFuture.addListener(f -> {
                if (!f.isSuccess()) {
                    resultFuture.completeExceptionally(f.cause());
                }
            });

            return resultFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    /**
     * Given the proxy's intent ({@code proxyWantsH2}) and the upstream's negotiated ALPN,
     * decide what ALPN protocols the proxy should advertise to clients.
     *
     * <ul>
     *   <li>{@code proxyWantsH2=false} → empty list (legacy: no ALPN advertised)</li>
     *   <li>{@code proxyWantsH2=true && upstreamProtocol="h2"} → [h2, http/1.1]</li>
     *   <li>{@code proxyWantsH2=true && upstreamProtocol!="h2"} → [http/1.1] (downgrade)</li>
     * </ul>
     */
    public static List<String> decideClientAdvertisement(boolean proxyWantsH2, String upstreamProtocol) {
        if (!proxyWantsH2) return List.of();
        if (ApplicationProtocolNames.HTTP_2.equals(upstreamProtocol)) {
            return List.of(ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1);
        }
        log.warn("Proxy started with --enableHttp2 but upstream selected '{}' — downgrading to advertise " +
                "http/1.1 only to clients. Either remove --enableHttp2 or upgrade the upstream to support h2.",
                upstreamProtocol);
        return List.of(ApplicationProtocolNames.HTTP_1_1);
    }
}
