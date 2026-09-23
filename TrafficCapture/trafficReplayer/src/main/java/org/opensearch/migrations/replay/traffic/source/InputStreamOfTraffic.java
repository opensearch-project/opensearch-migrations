package org.opensearch.migrations.replay.traffic.source;

// REBUILD-LIMBO(G2) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: ISimpleTrafficCaptureSource ITrafficSourceContexts ITrafficStreamKey PojoTrafficStreamAndKey PojoTrafficStreamKeyAndContext . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G2)
/*

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;
import org.opensearch.migrations.replay.tracing.ReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InputStreamOfTraffic implements ISimpleTrafficCaptureSource, AutoCloseable {
    private final BufferedReader bufferedReader;
    private final AtomicInteger trafficStreamsRead = new AtomicInteger();
    private final ChannelContextManager channelContextManager;

    public InputStreamOfTraffic(RootReplayerContext context, InputStream inputStream) {
        this.channelContextManager = new ChannelContextManager(context);
        var isr = new InputStreamReader(inputStream);
        try {
            this.bufferedReader = new BufferedReader(isr);
        } catch (Exception e) {
            try {
                isr.close();
            } catch (Exception e2) {
                log.atError().setCause(e2).setMessage("Caught exception while closing InputStreamReader that " +
                    "was in response to an earlier thrown exception.  Swallowing the inner exception and " +
                    "throwing the original one.").log();
            }
            throw e;
        }
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

*/
// REBUILD-LIMBO-END(G2)
    /**
     * Returns a CompletableFuture to a TrafficStream object or sets the cause exception to an
     * EOFException if the input has been exhausted.
     */
// REBUILD-LIMBO-START(G2)
/*
    @Override
    public CompletableFuture<List<SourceInput>> readNextTrafficStreamChunk(
        Supplier<ITrafficSourceContexts.IReadChunkContext> contextSupplier
    ) {
        return CompletableFuture.supplyAsync(() -> {
            TrafficStream ts;
            String line;
            try {
                line = bufferedReader.readLine();
                if (line == null) {
                    throw new EOFException();
                }
                ts = TrafficStream.parseFrom(Base64.getDecoder().decode(line));
            } catch (Exception e) {
                throw Lombok.sneakyThrow(e);
            }
            trafficStreamsRead.incrementAndGet();
            log.trace("Parsed traffic stream #{}: {}", trafficStreamsRead.get(), ts);
            return List.<SourceInput>of(
                new PojoTrafficStreamAndKey(ts, PojoTrafficStreamKeyAndContext.build(ts, tsk -> {
                    var channelCtx = channelContextManager.retainOrCreateContext(tsk);
                    return channelContextManager.getGlobalContext()
                        .createTrafficStreamContextForStreamSource(channelCtx, tsk);
                }))
            );
        }).exceptionally(e -> {
            var ecf = new CompletableFuture<List<SourceInput>>();
            ecf.completeExceptionally(e.getCause());
            return ecf.join();
        });
    }

    @Override
    public CompletionStage<Void> acknowledgeSessionTermination(ConnectionSessionKey sessionKey) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onConnectionAccumulationComplete(ITrafficStreamKey trafficStreamKey) {
        // Stream-backed input has no per-connection source registry.
    }

    @Override
    public void close() throws IOException {
        bufferedReader.close();
    }
}

*/
// REBUILD-LIMBO-END(G2)