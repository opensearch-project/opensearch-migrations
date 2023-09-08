package org.opensearch.migrations.testutils;

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
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslHandler;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * This class brings up an HTTP(s) server with its constructor that returns responses
 * based upon a simple Function that is passed to the constructor.  This class can support
 * TLS, but only with an auto-generated self-signed cert.
 */
@Slf4j
public class SimpleNettyHttpServer implements AutoCloseable {

    public static final String LOCALHOST = "localhost";

    EventLoopGroup bossGroup = new NioEventLoopGroup();
    EventLoopGroup workerGroup = new NioEventLoopGroup();

    public final boolean useTls;
    public final int port;
    private Channel serverChannel;

    public static SimpleNettyHttpServer makeServer(boolean useTls,
                                              Function<HttpFirstLine, SimpleHttpResponse> makeContext)
            throws PortFinder.ExceededMaxPortAssigmentAttemptException {
        var testServerRef = new AtomicReference<SimpleNettyHttpServer>();
        PortFinder.retryWithNewPortUntilNoThrow(port -> {
            try {
                testServerRef.set(new SimpleNettyHttpServer(useTls, port.intValue(), makeContext));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return testServerRef.get();
    }

    private static class RequestToFirstLineAdapter implements HttpFirstLine {
        private final FullHttpRequest request;

        public RequestToFirstLineAdapter(FullHttpRequest request) {
            this.request = request;
        }

        @Override
        public String verb() {
            return request.method().toString();
        }

        @SneakyThrows
        @Override
        public URI path() {
            return new URI(request.uri());
        }

        @Override
        public String version() {
            return request.protocolVersion().text();
        }
    }

    HttpHeaders convertHeaders(Map<String, String> headers) {
        var rval = new DefaultHttpHeaders();
        headers.entrySet().stream().forEach(kvp->rval.add(kvp.getKey(), kvp.getValue()));
        return rval;
    }

    private SimpleChannelInboundHandler<FullHttpRequest>
    makeHandlerFromResponseContext(Function<HttpFirstLine, SimpleHttpResponse> responseBuilder) {
        return new SimpleChannelInboundHandler<>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
                try {
                    var specifiedResponse = responseBuilder.apply(new RequestToFirstLineAdapter(req));
                    var fullResponse = new DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1,
                            HttpResponseStatus.valueOf(specifiedResponse.statusCode, specifiedResponse.statusText),
                            Unpooled.wrappedBuffer(specifiedResponse.payloadBytes),
                            convertHeaders(specifiedResponse.headers), null
                    );
                    ctx.writeAndFlush(fullResponse);
                } catch (Exception e) {
                    log.atDebug().setCause(e).log("Closing connection due to exception");
                    ctx.close();
                }
            }
        };
    }

    SimpleNettyHttpServer(boolean useTLS, int port, Function<HttpFirstLine, SimpleHttpResponse> responseBuilder)
            throws Exception {
        this.useTls = useTLS;
        this.port = port;
        final SSLContext javaSSLContext = useTLS ? SelfSignedSSLContextBuilder.getSSLContext() : null;

        var b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        var pipeline = ch.pipeline();
                        if (javaSSLContext != null) {
                            SSLEngine engine = javaSSLContext.createSSLEngine();
                            engine.setUseClientMode(false);
                            pipeline.addFirst("SSL", new SslHandler(engine));
                        }
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(16*1024));
                        pipeline.addLast(makeHandlerFromResponseContext(responseBuilder));
                        pipeline.addLast();
                    }
                });
        serverChannel = b.bind(port).sync().channel();
    }

    public int port() {
        return port;
    }

    public URI localhostEndpoint() {
        try {
            return new URI((useTls ? "https" : "http"), null, LOCALHOST, port(),"/",null, null);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Error building URI", e);
        }
    }

    @Override
    public void close() throws Exception {
        serverChannel.close();
        try {
            serverChannel.closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
}
