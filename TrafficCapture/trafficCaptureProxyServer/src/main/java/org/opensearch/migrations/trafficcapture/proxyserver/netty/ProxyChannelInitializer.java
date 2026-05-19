package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import javax.net.ssl.SSLEngine;

import java.io.IOException;
import java.util.function.Supplier;

import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.netty.ConditionallyReliableLoggingHttpHandler;
import org.opensearch.migrations.trafficcapture.netty.H2FrameSnifferHandler;
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
     * H2 pipeline (RFC 0001 §7.3 minimal-parse tee):
     * <ol>
     *   <li>{@link H2FrameSnifferHandler} — parses frame boundaries, emits per-frame
     *       {@code Http2FrameObservation}s via the per-connection capture serializer, and
     *       forwards bytes byte-identically.</li>
     *   <li>{@link FrontsideHandler} — forwards bytes to the upstream pool, identical to
     *       the H1 path.</li>
     * </ol>
     *
     * <p>Per-stream gating ({@code PerStreamGateHandler}) lands in T3.3 between the sniffer
     * and the forwarder. Until then, mutating-method streams are NOT held — the offload
     * guarantee from the H1 path doesn't apply yet to H2 traffic.
     *
     * <p>The capture serializer is created lazily here (one per connection) so it can emit
     * the {@code AlpnNegotiationObservation} synchronously before the first frame.
     */
    protected void configureH2Pipeline(ChannelHandlerContext ctx, String connectionId) throws IOException {
        var pipeline = ctx.pipeline();
        var serializer = createH2CaptureSerializer(connectionId);
        try {
            serializer.addAlpnNegotiatedEvent(
                java.time.Instant.now(),
                io.netty.handler.ssl.ApplicationProtocolNames.HTTP_2,
                io.netty.handler.ssl.ApplicationProtocolNames.HTTP_2 + ","
                    + io.netty.handler.ssl.ApplicationProtocolNames.HTTP_1_1);
        } catch (IOException e) {
            log.atWarn().setCause(e).setMessage("Failed to record ALPN observation for connectionId={}")
                .addArgument(connectionId).log();
        }
        // Read direction (client → proxy): sniff frames, emit observations, forward verbatim.
        pipeline.addLast("H2FrameSniffer-read",
            new H2FrameSnifferHandler(serializer,
                /*isClientToProxyDirection*/ true,
                this::onH2HeadersForGating,
                /*maxHeaderListSize*/ 8192L,
                /*maxHeaderTableSize*/ 4096L));
        afterCaptureHandlerInstalled(pipeline, connectionId);
        pipeline.addLast(new FrontsideHandler(backsideConnectionPool));
    }

    /**
     * Subclass hook fired when the sniffer decodes a HEADERS frame on the client→proxy side.
     * T3.3 ({@code PerStreamGateHandler}) will subscribe here to identify mutating-method
     * streams that need to be gated. Default: no-op.
     */
    protected void onH2HeadersForGating(int streamId, io.netty.handler.codec.http2.Http2Headers headers) {
        // Wired in T3.3 via PerStreamGateHandler subscription.
    }

    /**
     * Build the per-connection capture serializer for the H2 path. Subclasses may override
     * to wire alternate capture sinks (e.g., test in-memory factories).
     */
    protected IChannelConnectionCaptureSerializer<T> createH2CaptureSerializer(String connectionId) throws IOException {
        var parentContext = rootContext.createConnectionContext(connectionId, /*nodeId*/ "");
        return connectionCaptureFactory.createOffloader(parentContext);
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
