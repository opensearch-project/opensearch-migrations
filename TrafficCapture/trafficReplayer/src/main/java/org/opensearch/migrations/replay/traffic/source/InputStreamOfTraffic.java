package org.opensearch.migrations.replay.traffic.source;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;
import org.opensearch.migrations.replay.tracing.ReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InputStreamOfTraffic implements ISimpleTrafficCaptureSource {
    private final InputStream inputStream;
    private final AtomicInteger trafficStreamsRead = new AtomicInteger();
    private final ChannelContextManager channelContextManager;

    public InputStreamOfTraffic(RootReplayerContext context, InputStream inputStream) {
        this.channelContextManager = new ChannelContextManager(context);
        this.inputStream = inputStream;
    }

    public static final class IOSTrafficStreamContext extends ReplayContexts.TrafficStreamLifecycleContext {
        public IOSTrafficStreamContext(
            RootReplayerContext rootReplayerContext,
            IReplayContexts.IChannelKeyContext enclosingScope,
            ITrafficStreamKey trafficStreamKey
        ) {
            super(rootReplayerContext, enclosingScope, trafficStreamKey);
        }
    }

    /**
     * Returns a CompletableFuture to a TrafficStream object or sets the cause exception to an
     * EOFException if the input has been exhausted.
     */
    @Override
    public CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk(
        Supplier<ITrafficSourceContexts.IReadChunkContext> contextSupplier
    ) {
        return CompletableFuture.supplyAsync(() -> {
            var builder = TrafficStream.newBuilder();
            try {
                if (!builder.mergeDelimitedFrom(inputStream)) {
                    throw new EOFException();
                }
            } catch (Exception e) {
                throw Lombok.sneakyThrow(e);
            }
            var ts = builder.build();
            trafficStreamsRead.incrementAndGet();
            log.trace("Parsed traffic stream #{}: {}", trafficStreamsRead.get(), ts);
            return List.<ITrafficStreamWithKey>of(
                new PojoTrafficStreamAndKey(ts, PojoTrafficStreamKeyAndContext.build(ts, tsk -> {
                    var channelCtx = channelContextManager.retainOrCreateContext(tsk);
                    return channelContextManager.getGlobalContext()
                        .createTrafficStreamContextForStreamSource(channelCtx, tsk);
                }))
            );
        }).exceptionally(e -> {
            var ecf = new CompletableFuture<List<ITrafficStreamWithKey>>();
            ecf.completeExceptionally(e.getCause());
            return ecf.join();
        });
    }

    @Override
    public CommitResult commitTrafficStream(ITrafficStreamKey trafficStreamKey) {
        // do nothing - this datasource isn't transactional
        channelContextManager.releaseContextFor(trafficStreamKey.getTrafficStreamsContext().getLogicalEnclosingScope());
        return CommitResult.IMMEDIATE;
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
