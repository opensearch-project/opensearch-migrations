package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import javax.net.ssl.SSLEngine;

import java.io.IOException;
import java.util.function.Supplier;

import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.netty.ConditionallyReliableLoggingHttpHandler;
import org.opensearch.migrations.trafficcapture.netty.RequestCapturePredicate;
import org.opensearch.migrations.trafficcapture.netty.tracing.IRootWireLoggingContext;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.ssl.SslHandler;
import lombok.NonNull;

public class ProxyChannelInitializer<T> extends ChannelInitializer<SocketChannel> {
    protected static final String CAPTURE_HANDLER_NAME = "CaptureHandler";

    protected final IConnectionCaptureFactory<T> connectionCaptureFactory;
    protected final Supplier<SSLEngine> sslEngineProvider;
    protected final IRootWireLoggingContext rootContext;
    protected final BacksideConnectionPool backsideConnectionPool;
    protected final RequestCapturePredicate requestCapturePredicate;
    protected final boolean reliableCapture;
    protected final ConnectionLimitHandler connectionLimitHandler;

    public ProxyChannelInitializer(
        IRootWireLoggingContext rootContext,
        BacksideConnectionPool backsideConnectionPool,
        Supplier<SSLEngine> sslEngineSupplier,
        IConnectionCaptureFactory<T> connectionCaptureFactory,
        @NonNull RequestCapturePredicate requestCapturePredicate
    ) {
        this(rootContext, backsideConnectionPool, sslEngineSupplier, connectionCaptureFactory,
            requestCapturePredicate, true, 0);
    }

    public ProxyChannelInitializer(
        IRootWireLoggingContext rootContext,
        BacksideConnectionPool backsideConnectionPool,
        Supplier<SSLEngine> sslEngineSupplier,
        IConnectionCaptureFactory<T> connectionCaptureFactory,
        @NonNull RequestCapturePredicate requestCapturePredicate,
        boolean reliableCapture
    ) {
        this(rootContext, backsideConnectionPool, sslEngineSupplier, connectionCaptureFactory,
            requestCapturePredicate, reliableCapture, 0);
    }

    public ProxyChannelInitializer(
        IRootWireLoggingContext rootContext,
        BacksideConnectionPool backsideConnectionPool,
        Supplier<SSLEngine> sslEngineSupplier,
        IConnectionCaptureFactory<T> connectionCaptureFactory,
        @NonNull RequestCapturePredicate requestCapturePredicate,
        boolean reliableCapture,
        int maxConnections
    ) {
        this.rootContext = rootContext;
        this.backsideConnectionPool = backsideConnectionPool;
        this.sslEngineProvider = sslEngineSupplier;
        this.connectionCaptureFactory = connectionCaptureFactory;
        this.requestCapturePredicate = requestCapturePredicate;
        this.reliableCapture = reliableCapture;
        this.connectionLimitHandler = maxConnections > 0 ? new ConnectionLimitHandler(maxConnections) : null;
    }

    public boolean shouldGuaranteeMessageOffloading(HttpRequest httpRequest) {
        return reliableCapture
            && (httpRequest != null
            && (httpRequest.method().equals(HttpMethod.POST)
                || httpRequest.method().equals(HttpMethod.PUT)
                || httpRequest.method().equals(HttpMethod.DELETE)
                || httpRequest.method().equals(HttpMethod.PATCH)));
    }

    @Override
    protected void initChannel(@NonNull SocketChannel ch) throws IOException {
        if (connectionLimitHandler != null) {
            ch.pipeline().addLast(connectionLimitHandler);
        }
        var sslContext = sslEngineProvider != null ? sslEngineProvider.get() : null;
        if (sslContext != null) {
            ch.pipeline().addLast(new SslHandler(sslEngineProvider.get()));
        }

        var connectionId = ch.id().asLongText();
        ch.pipeline()
            .addLast(CAPTURE_HANDLER_NAME,
                new ConditionallyReliableLoggingHttpHandler<>(
                    rootContext,
                    "",
                    connectionId,
                    connectionCaptureFactory,
                    requestCapturePredicate,
                    this::shouldGuaranteeMessageOffloading
                )
            );
        ch.pipeline().addLast(new FrontsideHandler(backsideConnectionPool));
    }
}
