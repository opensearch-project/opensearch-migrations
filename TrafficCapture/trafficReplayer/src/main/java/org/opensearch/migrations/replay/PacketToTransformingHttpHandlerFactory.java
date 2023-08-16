package org.opensearch.migrations.replay;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformingConsumer;
import org.opensearch.migrations.transform.IAuthTransformer;
import org.opensearch.migrations.transform.IJsonTransformer;

import java.net.URI;

public class PacketToTransformingHttpHandlerFactory implements PacketConsumerFactory<AggregatedTransformedResponse> {
    private final NioEventLoopGroup eventLoopGroup;
    private final URI serverUri;
    private final IJsonTransformer jsonTransformer;
    private final IAuthTransformer authTransformer;
    private final SslContext sslContext;

    public PacketToTransformingHttpHandlerFactory(URI serverUri,
                                                  IJsonTransformer jsonTransformer,
                                                  IAuthTransformer authTransformer,
                                                  SslContext sslContext) {
        this.serverUri = serverUri;
        this.jsonTransformer = jsonTransformer;
        this.authTransformer = authTransformer;
        this.sslContext = sslContext;
        this.eventLoopGroup = new NioEventLoopGroup();
    }

    NettyPacketToHttpConsumer createNettyHandler(String diagnosticLabel) {
        return new NettyPacketToHttpConsumer(eventLoopGroup, serverUri, sslContext, diagnosticLabel);
    }

    @Override
    public IPacketFinalizingConsumer<AggregatedTransformedResponse> create(String diagnosticLabel) {
        return new HttpJsonTransformingConsumer(jsonTransformer, createNettyHandler(diagnosticLabel),
                authTransformer, diagnosticLabel);
    }

    public void stopGroup() {
        eventLoopGroup.shutdownGracefully();
    }
}
