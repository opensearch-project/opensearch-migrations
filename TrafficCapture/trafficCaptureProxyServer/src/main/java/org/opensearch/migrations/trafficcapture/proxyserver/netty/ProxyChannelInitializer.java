package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import java.io.IOException;
import java.util.function.Supplier;

import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.netty.ConditionallyReliableLoggingHttpHandler;
import org.opensearch.migrations.trafficcapture.netty.RequestCapturePredicate;
import org.opensearch.migrations.trafficcapture.netty.tracing.IRootWireLoggingContext;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.ssl.SslHandler;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProxyChannelInitializer<T> extends ChannelInitializer<SocketChannel> {
    protected static final String CAPTURE_HANDLER_NAME = "CaptureHandler";
    static final String TLS_HANDSHAKE_FAILURE_LOG_MESSAGE = "TLS handshake failed for frontside channel";

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
        var sslEngine = sslEngineProvider != null ? sslEngineProvider.get() : null;
        if (sslEngine != null) {
            ch.pipeline().addLast(new SslHandler(sslEngine));
            ch.pipeline().addLast(new FrontsideTlsExceptionHandler());
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

    private static class FrontsideTlsExceptionHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (!containsCause(cause, SSLException.class)) {
                ctx.fireExceptionCaught(cause);
                return;
            }

            log.atWarn().setCause(cause)
                .setMessage(TLS_HANDSHAKE_FAILURE_LOG_MESSAGE + ": {}")
                .addArgument(() -> ctx.channel().id().asLongText())
                .log();
            ctx.close();
        }

        private static boolean containsCause(Throwable cause, Class<? extends Throwable> causeType) {
            var current = cause;
            while (current != null) {
                if (causeType.isInstance(current)) {
                    return true;
                }
                current = current.getCause();
            }
            return false;
        }
    }
}
