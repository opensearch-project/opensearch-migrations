package org.opensearch.migrations.replay.datatypes;

import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.OnlineRadixSorter;

/**
 * This class contains everything that is needed to replay packets to a specific channel.
 * ConnectionClientPool and RequestSenderOrchestrator manage the data within these objects.
 * The ConnectionClientPool manages lifecycles, caching, and the underlying connection.  The
 * RequestSenderOrchestrator handles scheduling writes and requisite activities (prep, close)
 * that will go out on the channel.
 */
@Slf4j
public class ConnectionReplaySession {
    /**
     * We need to store this separately from the channelFuture because the channelFuture itself is
     * vended by a CompletableFuture (e.g. possibly a rate limiter).  If the ChannelFuture hasn't
     * been created yet, there's nothing to hold the channel, nor the eventLoop.  We _need_ the
     * EventLoop so that we can route all calls for this object into that loop/thread.
     */
    public final EventLoop eventLoop;
    public DiagnosticTrackableCompletableFuture<String,ChannelFuture> channelFutureFuture;
    public OnlineRadixSorter<Runnable> scheduleSequencer;
    public final TimeToResponseFulfillmentFutureMap schedule;

    public ConnectionReplaySession(EventLoop eventLoop) {
        this.eventLoop = eventLoop;
        this.scheduleSequencer = new OnlineRadixSorter(0);
        this.schedule = new TimeToResponseFulfillmentFutureMap();
    }

    @SneakyThrows
    public ChannelFuture getInnerChannelFuture() {
        return channelFutureFuture.get();
    }

    public boolean hasWorkRemaining() {
        return !schedule.isEmpty() || scheduleSequencer.hasPending();
    }

    public long calculateSizeSlowly() {
        return schedule.calculateSizeSlowly() + scheduleSequencer.numPending();
    }
}
