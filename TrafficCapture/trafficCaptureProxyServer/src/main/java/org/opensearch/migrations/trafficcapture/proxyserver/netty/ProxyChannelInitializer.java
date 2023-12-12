package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.ssl.SslHandler;
import lombok.NonNull;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.netty.ConditionallyReliableLoggingHttpRequestHandler;
import org.opensearch.migrations.trafficcapture.netty.RequestCapturePredicate;
import org.opensearch.migrations.trafficcapture.netty.LoggingHttpResponseHandler;

import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.util.function.Supplier;

public class ProxyChannelInitializer<T> extends ChannelInitializer<SocketChannel> {

    private final IConnectionCaptureFactory<T> connectionCaptureFactory;
    private final Supplier<SSLEngine> sslEngineProvider;
    private final BacksideConnectionPool backsideConnectionPool;
    private final RequestCapturePredicate requestCapturePredicate;

    public ProxyChannelInitializer(BacksideConnectionPool backsideConnectionPool, Supplier<SSLEngine> sslEngineSupplier,
                                   IConnectionCaptureFactory<T> connectionCaptureFactory,
                                   @NonNull RequestCapturePredicate requestCapturePredicate) {
        this.backsideConnectionPool = backsideConnectionPool;
        this.sslEngineProvider = sslEngineSupplier;
        this.connectionCaptureFactory = connectionCaptureFactory;
        this.requestCapturePredicate = requestCapturePredicate;
    }

    public boolean shouldGuaranteeMessageOffloading(HttpRequest httpRequest) {
        return (httpRequest != null &&
                (httpRequest.method().equals(HttpMethod.POST) ||
                        httpRequest.method().equals(HttpMethod.PUT) ||
                        httpRequest.method().equals(HttpMethod.DELETE) ||
                        httpRequest.method().equals(HttpMethod.PATCH)));
    }

    @Override
    protected void initChannel(SocketChannel ch) throws IOException {
        var sslContext = sslEngineProvider != null ? sslEngineProvider.get() : null;
        if (sslContext != null) {
            ch.pipeline().addLast(new SslHandler(sslEngineProvider.get()));
        }

        var offloader = connectionCaptureFactory.createOffloader(ch.id().asLongText());
        ch.pipeline().addLast(new LoggingHttpResponseHandler(offloader));
        ch.pipeline().addLast(new ConditionallyReliableLoggingHttpRequestHandler<T>(offloader,
                requestCapturePredicate, this::shouldGuaranteeMessageOffloading));
        ch.pipeline().addLast(new FrontsideHandler(backsideConnectionPool));
    }
}
