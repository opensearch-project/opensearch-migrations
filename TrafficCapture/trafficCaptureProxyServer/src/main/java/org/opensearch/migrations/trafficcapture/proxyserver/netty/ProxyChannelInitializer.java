package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import javax.net.ssl.SSLEngine;

import java.io.IOException;
import java.util.function.Supplier;

import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.netty.ConditionallyReliableLoggingHttpHandler;
import org.opensearch.migrations.trafficcapture.netty.RequestCapturePredicate;
import org.opensearch.migrations.trafficcapture.netty.tracing.IRootWireLoggingContext;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslHandler;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-connection pipeline initializer for the capture proxy.
 *
 * <p><b>Pipeline shape</b>: when ALPN selects {@code http/1.1} (or no ALPN was negotiated),
 * the pipeline is configured by {@link #configureH1Pipeline}, mirroring the historical
 * behavior. When ALPN selects {@code h2}, {@link #configureH2Pipeline} is invoked instead;
 * Phase 2 of RFC 0001 stubs that path out, Phase 3 wires the per-stream gate.
 *
 * <p><b>Deferred configuration</b>: when the channel has TLS but the SSL context advertises
 * ALPN, the pipeline is initially populated with only an {@link SslHandler} +
 * {@link ApplicationProtocolNegotiationHandler}. The negotiation handler fires
 * {@link ApplicationProtocolNegotiationHandler#configurePipeline} after the TLS handshake
 * completes and we know which protocol the peer picked.
 */
@Slf4j
public class ProxyChannelInitializer<T> extends ChannelInitializer<SocketChannel> {
    protected static final String CAPTURE_HANDLER_NAME = "CaptureHandler";

    protected final IConnectionCaptureFactory<T> connectionCaptureFactory;
    protected final Supplier<SSLEngine> sslEngineProvider;
    protected final IRootWireLoggingContext rootContext;
    protected final BacksideConnectionPool backsideConnectionPool;
    protected final RequestCapturePredicate requestCapturePredicate;

    public ProxyChannelInitializer(
        IRootWireLoggingContext rootContext,
        BacksideConnectionPool backsideConnectionPool,
        Supplier<SSLEngine> sslEngineSupplier,
        IConnectionCaptureFactory<T> connectionCaptureFactory,
        @NonNull RequestCapturePredicate requestCapturePredicate
    ) {
        this.rootContext = rootContext;
        this.backsideConnectionPool = backsideConnectionPool;
        this.sslEngineProvider = sslEngineSupplier;
        this.connectionCaptureFactory = connectionCaptureFactory;
        this.requestCapturePredicate = requestCapturePredicate;
    }

    public boolean shouldGuaranteeMessageOffloading(HttpRequest httpRequest) {
        return (httpRequest != null
            && (httpRequest.method().equals(HttpMethod.POST)
                || httpRequest.method().equals(HttpMethod.PUT)
                || httpRequest.method().equals(HttpMethod.DELETE)
                || httpRequest.method().equals(HttpMethod.PATCH)));
    }

    @Override
    protected void initChannel(@NonNull SocketChannel ch) throws IOException {
        var connectionId = ch.id().asLongText();
        var sslEngine = sslEngineProvider != null ? sslEngineProvider.get() : null;
        boolean alpnConfigured = sslEngine != null && isAlpnConfigured(sslEngine);

        if (sslEngine != null) {
            ch.pipeline().addLast(new SslHandler(sslEngine));
        }

        if (alpnConfigured) {
            // Defer pipeline configuration until ALPN selects a protocol.
            ch.pipeline().addLast(new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
                @Override
                protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
                    onAlpnNegotiated(ctx, protocol, connectionId);
                    try {
                        switch (protocol) {
                            case ApplicationProtocolNames.HTTP_2:
                                configureH2Pipeline(ctx, connectionId);
                                break;
                            case ApplicationProtocolNames.HTTP_1_1:
                            default:
                                configureH1Pipeline(ctx.pipeline(), connectionId);
                                break;
                        }
                    } catch (IOException e) {
                        log.atError().setCause(e).setMessage(
                                "Failed to configure pipeline after ALPN negotiation for connectionId={}")
                            .addArgument(connectionId).log();
                        ctx.close();
                    }
                }
            });
        } else {
            configureH1Pipeline(ch.pipeline(), connectionId);
        }
    }

    /**
     * Hook called once per connection after ALPN selection. Phase 2 emits no observation
     * here; T2.7 wires this through {@link IConnectionCaptureFactory} so the
     * {@code AlpnNegotiationObservation} lands in the capture stream before any frame.
     */
    protected void onAlpnNegotiated(ChannelHandlerContext ctx, String negotiatedProtocol, String connectionId) {
        log.atDebug().setMessage("ALPN negotiated for connectionId={}: {}")
            .addArgument(connectionId)
            .addArgument(negotiatedProtocol)
            .log();
    }

    /**
     * H1 pipeline: capture handler then forwarding handler. Identical to historical behavior.
     */
    protected void configureH1Pipeline(ChannelPipeline pipeline, String connectionId) throws IOException {
        pipeline.addLast(CAPTURE_HANDLER_NAME,
            new ConditionallyReliableLoggingHttpHandler<>(
                rootContext,
                "",
                connectionId,
                connectionCaptureFactory,
                requestCapturePredicate,
                this::shouldGuaranteeMessageOffloading
            )
        );
        afterCaptureHandlerInstalled(pipeline, connectionId);
        pipeline.addLast(new FrontsideHandler(backsideConnectionPool));
    }

    /**
     * Subclass hook called once the capture handler is installed on the pipeline. Subclasses use
     * this to add header-rewrite handlers via {@code pipeline.addAfter(CAPTURE_HANDLER_NAME, …)}.
     * Default no-op.
     */
    protected void afterCaptureHandlerInstalled(ChannelPipeline pipeline, String connectionId) {
        // no-op for the base initializer
    }

    /**
     * H2 pipeline: stubbed in Phase 2 (RFC 0001 T2.3). T2.7 fills this in with the
     * minimal-parse sniffer + per-stream gate. Until then the connection is closed
     * with an explanatory exception so an H2 client never sees ambiguous behavior.
     */
    protected void configureH2Pipeline(ChannelHandlerContext ctx, String connectionId) {
        log.atWarn().setMessage(
                "ALPN negotiated h2 for connectionId={}, but the H2 pipeline is not yet wired in this build. "
                    + "Closing connection. (RFC 0001 T2.7 will land the H2 pipeline.)")
            .addArgument(connectionId).log();
        ctx.close();
    }

    /**
     * Inspect the SSLEngine to determine whether ALPN was configured on the underlying
     * SslContext. {@link SSLEngine#getSSLParameters()} doesn't reliably report ALPN
     * protocols when Netty wraps the engine, so as a secondary signal we recognize the
     * Netty engine wrapper class names that always indicate an ALPN-configured context.
     */
    private static boolean isAlpnConfigured(SSLEngine engine) {
        var params = engine.getSSLParameters();
        var protos = params == null ? null : params.getApplicationProtocols();
        if (protos != null && protos.length > 0) {
            return true;
        }
        // Fallback: detect Netty's ALPN-aware engine wrappers by class name. ApplicationProtocolAccessor
        // is package-private so we can't refer to it directly.
        var simpleName = engine.getClass().getSimpleName();
        return "OpenSslEngine".equals(simpleName) || simpleName.contains("Alpn");
    }
}
