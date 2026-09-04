package org.opensearch.migrations.replay.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;

import lombok.NonNull;

public interface ActorMailbox extends Executor {
    interface ScheduledTask {
        boolean cancel();
    }

    boolean inMailbox();

    Instant now();

    ScheduledTask schedule(@NonNull Runnable command, @NonNull Duration delay);
}
