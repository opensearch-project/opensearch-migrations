package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datahandlers.TransformedPacketReceiver;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformingConsumer;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.datatypes.TransformedPackets;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;

@Slf4j
public class PacketToTransformingHttpHandlerFactory implements
        PacketConsumerFactory<TransformedOutputAndResult<TransformedPackets>> {
    private final IJsonTransformer jsonTransformer;
    private final IAuthTransformerFactory authTransformerFactory;

    public PacketToTransformingHttpHandlerFactory(IJsonTransformer jsonTransformer,
                                                  IAuthTransformerFactory authTransformerFactory) {
        this.jsonTransformer = jsonTransformer;
        this.authTransformerFactory = authTransformerFactory;
    }


    @Override
    public IPacketFinalizingConsumer<TransformedOutputAndResult<TransformedPackets>>
    create(UniqueReplayerRequestKey requestKey) {
        log.trace("creating HttpJsonTransformingConsumer");
        return new HttpJsonTransformingConsumer(jsonTransformer, authTransformerFactory,
                new TransformedPacketReceiver(), requestKey.toString(), requestKey);
    }
}
