package org.opensearch.migrations.replay.datatypes;

import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.ReplayContexts;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.OnlineRadixSorter;

import java.util.concurrent.atomic.AtomicReference;

/**
 * This class contains everything that is needed to replay packets to a specific channel.
 * ConnectionClientPool and RequestSenderOrchestrator manage the data within these objects.
 * The ConnectionClientPool manages lifecycles, caching, and the underlying connection.  The
 * RequestSenderOrchestrator handles scheduling writes and requisite activities (prep, close)
 * that will go out on the channel.
 */
@Slf4j
public class ConnectionReplaySession implements AutoCloseable {

    /**
     * We need to store this separately from the channelFuture because the channelFuture itself is
     * vended by a CompletableFuture (e.g. possibly a rate limiter).  If the ChannelFuture hasn't
     * been created yet, there's nothing to hold the channel, nor the eventLoop.  We _need_ the
     * EventLoop so that we can route all calls for this object into that loop/thread.
     */
    public final EventLoop eventLoop;
    @Getter
    @Setter
    private DiagnosticTrackableCompletableFuture<String, ChannelFuture> channelFutureFuture;
    public final OnlineRadixSorter<Runnable> scheduleSequencer;
    public final TimeToResponseFulfillmentFutureMap schedule;
    private final AtomicReference<IReplayContexts.ISocketContext> socketContext;

    public ConnectionReplaySession(EventLoop eventLoop, IReplayContexts.IChannelKeyContext channelKeyContext) {
        this.eventLoop = eventLoop;
        this.scheduleSequencer = new OnlineRadixSorter<>(0);
        this.schedule = new TimeToResponseFulfillmentFutureMap();
        this.socketContext = new AtomicReference<>(channelKeyContext.createSocketContext());
    }

    @SneakyThrows
    public ChannelFuture getInnerChannelFuture() {
        return channelFutureFuture.get();
    }

    public IReplayContexts.ISocketContext getSocketContext() {
        return socketContext.get();
    }

    public boolean hasWorkRemaining() {
        return scheduleSequencer.hasPending() || schedule.hasPendingTransmissions();
    }

    public long calculateSizeSlowly() {
        return schedule.calculateSizeSlowly() + scheduleSequencer.numPending();
    }

    @Override
    public void close() throws Exception {
        var oldVal = socketContext.getAndSet(null);
        if (oldVal != null) {
            oldVal.close();
        }
    }
}
