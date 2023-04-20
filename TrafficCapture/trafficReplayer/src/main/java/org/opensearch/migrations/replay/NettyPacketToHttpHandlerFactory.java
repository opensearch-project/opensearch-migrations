package org.opensearch.migrations.replay;

import io.netty.channel.nio.NioEventLoopGroup;

import java.io.IOException;
import java.net.URI;

public class NettyPacketToHttpHandlerFactory {
    private final NioEventLoopGroup eventLoopGroup;
    private final URI serverUri;

    public NettyPacketToHttpHandlerFactory(URI serverUri) {
        this.eventLoopGroup = new NioEventLoopGroup();
        this.serverUri = serverUri;
    }

    public IPacketToHttpHandler create() throws IOException {
        var nettyHandler = new NettyPacketToHttpHandler(eventLoopGroup, serverUri);
        return new HttpMessageTransformerHandler(nettyHandler);
    }

    public void stopGroup() {
        eventLoopGroup.shutdownGracefully();
    }
}
