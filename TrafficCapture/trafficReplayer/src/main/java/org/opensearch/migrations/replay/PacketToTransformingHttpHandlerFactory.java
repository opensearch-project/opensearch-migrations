package org.opensearch.migrations.replay;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datahandlers.TransformedPacketReceiver;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformingConsumer;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PacketToTransformingHttpHandlerFactory
    implements PacketConsumerFactory<TransformedOutputAndResult<ByteBufList>>, AutoCloseable {

    // Using ThreadLocal to ensure thread safety with the json transformers which will be reused
    private final ThreadLocal<IJsonTransformer> localJsonTransformer;
    private final Set<AutoCloseable> closeableResources = ConcurrentHashMap.newKeySet();

    // The authTransformerFactory is ThreadSafe and getAuthTransformer will be called for every request
    private final IAuthTransformerFactory authTransformerFactory;

    public PacketToTransformingHttpHandlerFactory(
        Supplier<IJsonTransformer> jsonTransformerSupplier,
        IAuthTransformerFactory authTransformerFactory
    ) {
        this.localJsonTransformer = ThreadLocal.withInitial(jsonTransformerSupplier);
        this.authTransformerFactory = authTransformerFactory;
    }

    @Override
    public IPacketFinalizingConsumer<TransformedOutputAndResult<ByteBufList>> create(
        IReplayContexts.IReplayerHttpTransactionContext httpTransactionContext
    ) {
        log.trace("creating HttpJsonTransformingConsumer");
        return new HttpJsonTransformingConsumer<>(
            localJsonTransformer.get(),
            authTransformerFactory,
            new TransformedPacketReceiver(),
            httpTransactionContext
        );
    }

    @Override
    public void close() throws Exception {
        for (AutoCloseable resource : closeableResources) {
            resource.close();
        }
        localJsonTransformer.remove();
    }
}
