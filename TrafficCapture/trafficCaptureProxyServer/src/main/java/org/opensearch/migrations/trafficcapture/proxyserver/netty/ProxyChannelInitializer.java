package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectDecoder;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.netty.ConditionallyReliableLoggingHttpRequestHandler;
import org.opensearch.migrations.trafficcapture.netty.LoggingHttpRequestHandler;
import org.opensearch.migrations.trafficcapture.netty.LoggingHttpResponseHandler;

import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.net.URI;
import java.util.function.Supplier;

public class ProxyChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final IConnectionCaptureFactory connectionCaptureFactory;
    private final Supplier<SSLEngine> sslEngineProvider;
    private final URI backsideUri;
    private final SslContext backsideSslContext;

    public ProxyChannelInitializer(URI backsideUri, SslContext backsideSslContext, Supplier<SSLEngine> sslEngineSupplier,
                                   IConnectionCaptureFactory connectionCaptureFactory) {
        this.backsideUri = backsideUri;
        this.backsideSslContext = backsideSslContext;
        this.sslEngineProvider = sslEngineSupplier;
        this.connectionCaptureFactory = connectionCaptureFactory;
    }

    public boolean shouldGuaranteeMessageOffloading(HttpRequest httpRequest) {
        return (httpRequest != null &&
                (httpRequest.method().equals(HttpMethod.POST) ||
                        httpRequest.method().equals(HttpMethod.PUT) ||
                        httpRequest.method().equals(HttpMethod.DELETE)));
    }

    @Override
    protected void initChannel(SocketChannel ch) throws IOException {
        var sslContext = sslEngineProvider != null ? sslEngineProvider.get() : null;
        if (sslContext != null) {
            ch.pipeline().addLast(new SslHandler(sslEngineProvider.get()));
        }

        var offloader = connectionCaptureFactory.createOffloader(ch.id().asLongText());
        //ch.pipeline().addLast(new LoggingHandler("PRE", LogLevel.WARN));
        ch.pipeline().addLast(new LoggingHttpResponseHandler(offloader));
        ch.pipeline().addLast(new ConditionallyReliableLoggingHttpRequestHandler(offloader,
                this::shouldGuaranteeMessageOffloading));
        //ch.pipeline().addLast(new LoggingHandler("POST", LogLevel.ERROR));
        ch.pipeline().addLast(new FrontsideHandler(backsideUri, backsideSslContext));
    }
}
