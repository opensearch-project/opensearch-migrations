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
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class InputStreamOfTraffic implements ISimpleTrafficCaptureSource {
    private final InputStream inputStream;
    private final AtomicInteger trafficStreamsRead = new AtomicInteger();
    private final ChannelContextManager channelContextManager;

    public InputStreamOfTraffic(IInstrumentationAttributes<RootReplayerContext> context, InputStream inputStream) {
        this.channelContextManager = new ChannelContextManager(context);
        this.inputStream = inputStream;
    }

    public static class IOSTrafficStreamContext
            extends DirectNestedSpanContext<RootReplayerContext, IReplayContexts.IChannelKeyContext<RootReplayerContext>>
            implements IReplayContexts.ITrafficStreamsLifecycleContext<RootReplayerContext> {
        @Getter private final ITrafficStreamKey trafficStreamKey;

        public IOSTrafficStreamContext(@NonNull IReplayContexts.IChannelKeyContext<RootReplayerContext> ctx,
                                       ITrafficStreamKey tsk) {
            super(ctx);
            this.trafficStreamKey = tsk;
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            final LongUpDownCounter activeChannelCounter;
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
                var meter = meterProvider.get(SCOPE_NAME2);
                activeChannelCounter = meter
                        .upDownCounterBuilder(IReplayContexts.MetricNames.ACTIVE_TARGET_CONNECTIONS).build();
            }
        }

        public @NonNull ReplayContexts.ChannelKeyContext.MetricInstruments getMetrics() {
            return getRootInstrumentationScope().channelKeyContext;
        }

        @Override
        public String getActivityName() { return "trafficStreamLifecycle"; }

        @Override
        public IReplayContexts.IChannelKeyContext<RootReplayerContext> getChannelKeyContext() {
            return getImmediateEnclosingScope();
        }
    }

    /**
     * Returns a CompletableFuture to a TrafficStream object or sets the cause exception to an
     * EOFException if the input has been exhausted.
     *
     * @return
     */
    public CompletableFuture<List<ITrafficStreamWithKey>>
    readNextTrafficStreamChunk(IInstrumentationAttributes<RootReplayerContext> context) {
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
    public CommitResult commitTrafficStream(IInstrumentationAttributes ctx, ITrafficStreamKey trafficStreamKey) {
        // do nothing - this datasource isn't transactional
        return CommitResult.Immediate;
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
