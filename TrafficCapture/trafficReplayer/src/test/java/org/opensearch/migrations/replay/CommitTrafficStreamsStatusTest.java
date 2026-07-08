package org.opensearch.migrations.replay;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.http.retries.IRetryVisitorFactory;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.IRootReplayerContext;
import org.opensearch.migrations.replay.traffic.source.ITrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamLimiter;
import org.opensearch.migrations.transform.IJsonTransformer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests that TrafficReplayerCore.commitTrafficStreams(status, keys) commits or suppresses
 * the offset based on the ReconstructionStatus — exercising the ACTUAL production code path.
 *
 * CLOSED_PREMATURELY must commit (suppressing it causes head-of-line blocking).
 * TRAFFIC_SOURCE_READER_INTERRUPTED must NOT commit (partition was reassigned).
 * All other statuses must commit.
 */
class CommitTrafficStreamsStatusTest {

    static class TestableReplayerCore extends TrafficReplayerCore {
        @SuppressWarnings("unchecked")
        TestableReplayerCore() {
            super(
                mock(IRootReplayerContext.class),
                URI.create("http://localhost:9200"),
                null,
                () -> mock(IJsonTransformer.class),
                mock(TrafficStreamLimiter.class),
                mock(TrafficReplayerCore.IWorkTracker.class),
                mock(IRetryVisitorFactory.class)
            );
        }

        @Override
        protected CompletableFuture<Void> shutdown(Error error) {
            return CompletableFuture.completedFuture(null);
        }

        TrafficReplayerAccumulationCallbacks createCallbacks(ITrafficCaptureSource source) {
            return new TrafficReplayerAccumulationCallbacks(
                mock(ReplayEngine.class), null, null, null, source, Duration.ZERO
            );
        }
    }

    @Test
    void closedPrematurelyStatus_commitsOffset() throws IOException {
        var core = new TestableReplayerCore();
        var source = mock(ITrafficCaptureSource.class);
        var callbacks = core.createCallbacks(source);
        var tsk = mock(ITrafficStreamKey.class);
        var ctx = mock(IReplayContexts.IChannelKeyContext.class);
        var tsCtx = mock(IReplayContexts.ITrafficStreamsLifecycleContext.class);
        org.mockito.Mockito.when(tsk.getTrafficStreamsContext()).thenReturn(tsCtx);

        callbacks.onTrafficStreamsExpired(
            RequestResponsePacketPair.ReconstructionStatus.CLOSED_PREMATURELY,
            ctx,
            List.of(tsk)
        );

        verify(source).commitTrafficStream(tsk);
    }

    @Test
    void trafficSourceReaderInterrupted_suppressesCommit() throws IOException {
        var core = new TestableReplayerCore();
        var source = mock(ITrafficCaptureSource.class);
        var callbacks = core.createCallbacks(source);
        var tsk = mock(ITrafficStreamKey.class);
        var ctx = mock(IReplayContexts.IChannelKeyContext.class);
        var tsCtx = mock(IReplayContexts.ITrafficStreamsLifecycleContext.class);
        org.mockito.Mockito.when(tsk.getTrafficStreamsContext()).thenReturn(tsCtx);

        callbacks.onTrafficStreamsExpired(
            RequestResponsePacketPair.ReconstructionStatus.TRAFFIC_SOURCE_READER_INTERRUPTED,
            ctx,
            List.of(tsk)
        );

        verify(source, never()).commitTrafficStream(tsk);
    }

    @ParameterizedTest
    @EnumSource(value = RequestResponsePacketPair.ReconstructionStatus.class,
        names = {"TRAFFIC_SOURCE_READER_INTERRUPTED"}, mode = EnumSource.Mode.EXCLUDE)
    void allNonInterruptedStatuses_commitOffset(RequestResponsePacketPair.ReconstructionStatus status)
        throws IOException {
        var core = new TestableReplayerCore();
        var source = mock(ITrafficCaptureSource.class);
        var callbacks = core.createCallbacks(source);
        var tsk = mock(ITrafficStreamKey.class);
        var ctx = mock(IReplayContexts.IChannelKeyContext.class);
        var tsCtx = mock(IReplayContexts.ITrafficStreamsLifecycleContext.class);
        org.mockito.Mockito.when(tsk.getTrafficStreamsContext()).thenReturn(tsCtx);

        callbacks.onTrafficStreamsExpired(status, ctx, List.of(tsk));

        verify(source).commitTrafficStream(tsk);
    }
}
