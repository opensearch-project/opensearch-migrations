package org.opensearch.migrations.replay;

import io.netty.channel.nio.NioEventLoopGroup;
import org.opensearch.migrations.replay.datahandlers.IPacketToHttpHandler;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpHandler;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformerHandler;
import org.opensearch.migrations.transform.JsonTransformer;

import java.io.IOException;
import java.net.URI;

public class PacketToTransformingProxyHandlerFactory {
    private final NioEventLoopGroup eventLoopGroup;
    private final URI serverUri;
    private final JsonTransformer jsonTransformer;

    public PacketToTransformingProxyHandlerFactory(URI serverUri, JsonTransformer jsonTransformer) {
        this.jsonTransformer = jsonTransformer;
        this.eventLoopGroup = new NioEventLoopGroup();
        this.serverUri = serverUri;
    }

    public IPacketToHttpHandler createNettyHandler() {
        return new NettyPacketToHttpHandler(eventLoopGroup, serverUri);
    }

    public IPacketToHttpHandler create() {
        return new HttpJsonTransformerHandler(jsonTransformer, createNettyHandler());
    }

    public void stopGroup() {
        eventLoopGroup.shutdownGracefully();
    }
}
