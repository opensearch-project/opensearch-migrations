package org.opensearch.migrations.replay;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.opensearch.migrations.replay.datahandlers.IPacketToHttpHandler;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpHandler;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformer;
import org.opensearch.migrations.transform.JsonTransformer;

import java.net.URI;

public class PacketToTransformingProxyHandlerFactory {
    private final NioEventLoopGroup eventLoopGroup;
    private final URI serverUri;
    private final JsonTransformer jsonTransformer;
    private final SslContext sslContext;

    public PacketToTransformingProxyHandlerFactory(URI serverUri, JsonTransformer jsonTransformer,
                                                   SslContext sslContext) {
        this.jsonTransformer = jsonTransformer;
        this.eventLoopGroup = new NioEventLoopGroup();
        this.serverUri = serverUri;
        this.sslContext = sslContext;
    }

    public IPacketToHttpHandler createNettyHandler() {
        return new NettyPacketToHttpHandler(eventLoopGroup, serverUri, sslContext);
    }

    public HttpJsonTransformer create() {
        return new HttpJsonTransformer(jsonTransformer, createNettyHandler());
    }

    public void stopGroup() {
        eventLoopGroup.shutdownGracefully();
    }
}
