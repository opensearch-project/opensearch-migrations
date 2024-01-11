package org.opensearch.migrations.replay.traffic.source;

import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.MeterProvider;
import lombok.Getter;
import lombok.Lombok;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.IKafkaConsumerContexts;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;
import org.opensearch.migrations.replay.tracing.ReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

@Slf4j
public class InputStreamOfTraffic implements ISimpleTrafficCaptureSource {
    private final InputStream inputStream;
    private final AtomicInteger trafficStreamsRead = new AtomicInteger();
    private final ChannelContextManager channelContextManager;

    public InputStreamOfTraffic(RootReplayerContext context, InputStream inputStream) {
        this.channelContextManager = new ChannelContextManager(context);
        this.inputStream = inputStream;
    }

    public static class IOSTrafficStreamContext
            extends DirectNestedSpanContext<RootReplayerContext, ReplayContexts.ChannelKeyContext, IReplayContexts.IChannelKeyContext>
            implements IReplayContexts.ITrafficStreamsLifecycleContext {
        @Getter private final ITrafficStreamKey trafficStreamKey;

        public IOSTrafficStreamContext(@NonNull ReplayContexts.ChannelKeyContext ctx,
                                       ITrafficStreamKey tsk) {
            super(ctx);
            this.trafficStreamKey = tsk;
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
                var meter = meterProvider.get(SCOPE_NAME2);
            }
        }

        public @NonNull ReplayContexts.ChannelKeyContext.MetricInstruments getMetrics() {
            return getRootInstrumentationScope().channelKeyContext;
        }

        @Override
        public ReplayContexts.HttpTransactionContext createHttpTransactionContext(UniqueReplayerRequestKey requestKey,
                                                                                  Instant sourceTimestamp) {
            return new ReplayContexts.HttpTransactionContext(getRootInstrumentationScope(),
                    this, requestKey, sourceTimestamp);
        }

        @Override
        public String getActivityName() { return "trafficStreamLifecycle"; }

        @Override
        public IReplayContexts.IChannelKeyContext getChannelKeyContext() {
            return getImmediateEnclosingScope();
        }
    }

    /**
     * Returns a CompletableFuture to a TrafficStream object or sets the cause exception to an
     * EOFException if the input has been exhausted.
     */
    @Override
    public CompletableFuture<List<ITrafficStreamWithKey>>
    readNextTrafficStreamChunk(Supplier<ITrafficSourceContexts.IReadChunkContext> contextSupplier) {
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
    public CommitResult commitTrafficStream(Function<ITrafficStreamKey,
                                                     IKafkaConsumerContexts.ICommitScopeContext> ctx,
                                            ITrafficStreamKey trafficStreamKey) {
        // do nothing - this datasource isn't transactional
        return CommitResult.Immediate;
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
