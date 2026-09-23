package org.opensearch.migrations.replay.lifecycle;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Carried verbatim. This was the pre-rebuild implementation of a responsibility the design
// reassigns, so it is the input to that refactor rather than something to re-derive. Resolve it to
// dead, keep, or refactor deliberately -- see AGENTS.md section 8a, and read this before writing

// REBUILD-LIMBO-START(G11)
/*

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import io.netty.channel.EventLoop;
import lombok.NonNull;

public final class NettyEventLoopActorMailbox implements ActorMailbox {
    private final EventLoop eventLoop;
    private final Clock clock;

    public NettyEventLoopActorMailbox(@NonNull EventLoop eventLoop) {
        this(eventLoop, Clock.systemUTC());
    }

    NettyEventLoopActorMailbox(@NonNull EventLoop eventLoop, @NonNull Clock clock) {
        this.eventLoop = eventLoop;
        this.clock = clock;
    }

    @Override
    public void execute(Runnable command) {
        eventLoop.execute(command);
    }

    @Override
    public boolean inMailbox() {
        return eventLoop.inEventLoop();
    }

    @Override
    public Instant now() {
        return clock.instant();
    }

    @Override
    public ScheduledTask schedule(@NonNull Runnable command, @NonNull Duration delay) {
        var future = eventLoop.schedule(command, Math.max(0, delay.toNanos()), TimeUnit.NANOSECONDS);
        return () -> future.cancel(false);
    }
}

*/
// REBUILD-LIMBO-END(G11)