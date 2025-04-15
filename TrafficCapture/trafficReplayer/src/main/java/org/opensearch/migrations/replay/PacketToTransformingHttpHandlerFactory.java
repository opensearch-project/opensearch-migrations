package org.opensearch.migrations.replay;

import java.util.function.Supplier;

import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datahandlers.TransformedPacketReceiver;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonTransformingConsumer;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.ThreadSafeTransformerWrapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Factory for creating packet consumers that transform HTTP content using a per-thread {@link IJsonTransformer}.
 * <p>
 * The {@link ThreadSafeTransformerWrapper} ensures each thread gets its own transformer instance.
 * It is important to call {@link #close()} once a thread is done using the factory to release any
 * thread-local resources deterministically.
 * <p>
 * Failure to call {@code close()} may result in delayed cleanup in long-lived thread pools.
 */
@Slf4j
public class PacketToTransformingHttpHandlerFactory
    implements PacketConsumerFactory<TransformedOutputAndResult<ByteBufList>>, AutoCloseable {

    private final ThreadSafeTransformerWrapper threadSafeTransformer;

    // The authTransformerFactory is ThreadSafe and getAuthTransformer will be called for every request
    private final IAuthTransformerFactory authTransformerFactory;

    public PacketToTransformingHttpHandlerFactory(
        Supplier<IJsonTransformer> jsonTransformerSupplier,
        IAuthTransformerFactory authTransformerFactory
    ) {
        this.threadSafeTransformer = new ThreadSafeTransformerWrapper(jsonTransformerSupplier);
        this.authTransformerFactory = authTransformerFactory;
    }

    @Override
    public IPacketFinalizingConsumer<TransformedOutputAndResult<ByteBufList>> create(
        IReplayContexts.IReplayerHttpTransactionContext httpTransactionContext
    ) {
        log.trace("creating HttpJsonTransformingConsumer");
        return new HttpJsonTransformingConsumer<>(
            threadSafeTransformer,
            authTransformerFactory,
            new TransformedPacketReceiver(),
            httpTransactionContext
        );
    }

    @Override
    public void close() throws Exception {
        threadSafeTransformer.close();
    }
}
