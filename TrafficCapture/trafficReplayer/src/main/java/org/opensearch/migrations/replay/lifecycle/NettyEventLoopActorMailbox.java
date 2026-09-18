package org.opensearch.migrations.replay.lifecycle;

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
