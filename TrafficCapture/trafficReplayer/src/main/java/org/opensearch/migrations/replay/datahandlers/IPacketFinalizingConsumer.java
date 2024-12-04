package org.opensearch.migrations.replay.datahandlers;

import org.opensearch.migrations.utils.TrackedFuture;

/**
 * This class consumes arrays of bytes or ByteBufs, potentially asynchronously,
 * whose completion is signaled via the CompletableFuture that is returned.
 * When the caller has pushed all the data that they're interested in pushing,
 * finalizeRequest() should be called, which also returns a CompletableFuture to
 * indicate that all previous consumeBytes() have finished.
 */
public interface IPacketFinalizingConsumer<R> extends IPacketConsumer {

    TrackedFuture<String, R> finalizeRequest();
}
