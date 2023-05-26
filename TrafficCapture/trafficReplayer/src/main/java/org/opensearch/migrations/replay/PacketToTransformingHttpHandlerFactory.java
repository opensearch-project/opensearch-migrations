package org.opensearch.migrations.replay;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformingConsumer;
import org.opensearch.migrations.transform.JsonTransformer;

import java.net.URI;

public class PacketToTransformingHttpHandlerFactory implements PacketConsumerFactory<AggregatedTransformedResponse> {
    private final NioEventLoopGroup eventLoopGroup;
    private final URI serverUri;
    private final JsonTransformer jsonTransformer;
    private final SslContext sslContext;

    public PacketToTransformingHttpHandlerFactory(URI serverUri, JsonTransformer jsonTransformer,
                                                  SslContext sslContext) {
        this.jsonTransformer = jsonTransformer;
        this.eventLoopGroup = new NioEventLoopGroup();
        this.serverUri = serverUri;
        this.sslContext = sslContext;
    }

    IPacketFinalizingConsumer createNettyHandler() {
        return new NettyPacketToHttpConsumer(eventLoopGroup, serverUri, sslContext);
    }

    @Override
    public IPacketFinalizingConsumer<AggregatedTransformedResponse> create() {
        return new HttpJsonTransformingConsumer(jsonTransformer, createNettyHandler());
    }

    public void stopGroup() {
        eventLoopGroup.shutdownGracefully();
    }
}
