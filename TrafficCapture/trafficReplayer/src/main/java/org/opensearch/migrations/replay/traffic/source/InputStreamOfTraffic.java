package org.opensearch.migrations.replay.traffic.source;

import lombok.Getter;
import lombok.Lombok;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.replay.tracing.IChannelKeyContext;
import org.opensearch.migrations.replay.tracing.IContexts;
import org.opensearch.migrations.tracing.SimpleMeteringClosure;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class InputStreamOfTraffic implements ISimpleTrafficCaptureSource {
    private static final SimpleMeteringClosure METERING_CLOSURE = new SimpleMeteringClosure("InputStreamOfTraffic");
    private final InputStream inputStream;
    private final AtomicInteger trafficStreamsRead = new AtomicInteger();
    private final ChannelContextManager channelContextManager;

    public InputStreamOfTraffic(InputStream inputStream) {
        this.channelContextManager = new ChannelContextManager();
        this.inputStream = inputStream;
    }

    private static class IOSTrafficStreamContext
            extends DirectNestedSpanContext<IChannelKeyContext>
            implements IContexts.ITrafficStreamsLifecycleContext {
        @Getter private final ITrafficStreamKey trafficStreamKey;

        public IOSTrafficStreamContext(@NonNull IChannelKeyContext ctx, ITrafficStreamKey tsk) {
            super(ctx);
            this.trafficStreamKey = tsk;
            setCurrentSpan(METERING_CLOSURE.makeSpanContinuation("trafficStreamLifecycle"));
        }

        @Override
        public IChannelKeyContext getChannelKeyContext() {
            return getImmediateEnclosingScope();
        }
    }

    /**
     * Returns a CompletableFuture to a TrafficStream object or sets the cause exception to an
     * EOFException if the input has been exhausted.
     *
     * @return
     */
    public CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk() {
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
            return List.<ITrafficStreamWithKey>of(new PojoTrafficStreamAndKey(ts,
                    PojoTrafficStreamKeyAndContext.build(ts, tsk-> {
                        var channelCtx = channelContextManager.retainOrCreateContext(tsk);
                        return new IOSTrafficStreamContext(channelCtx, tsk);
                    })));
        }).exceptionally(e->{
            var ecf = new CompletableFuture<List<ITrafficStreamWithKey>>();
            ecf.completeExceptionally(e.getCause());
            return ecf.join();
        });
    }

    @Override
    public void commitTrafficStream(ITrafficStreamKey trafficStreamKey) {
        // do nothing - this datasource isn't transactional
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
