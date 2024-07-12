package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.io.IOException;
import java.util.function.Supplier;
import javax.net.ssl.SSLEngine;

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
    protected void initChannel(SocketChannel ch) throws IOException {
        var sslContext = sslEngineProvider != null ? sslEngineProvider.get() : null;
        if (sslContext != null) {
            ch.pipeline().addLast(new SslHandler(sslEngineProvider.get()));
        }

        var connectionId = ch.id().asLongText();
        ch.pipeline()
            .addLast(
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
