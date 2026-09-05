package org.opensearch.migrations.replay.tracing;

import java.time.Duration;

import org.opensearch.migrations.replay.lifecycle.ConnectionActor;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

public final class ConnectionActorMetrics implements ConnectionActor.Metrics {
    public static final AttributeKey<String> REASON_ATTRIBUTE = AttributeKey.stringKey("reason");
    public static final AttributeKey<String> CHILD_ATTRIBUTE = AttributeKey.stringKey("child");

    public static final class MetricNames {
        private MetricNames() {}

        public static final String QUEUED_COMMANDS = "connectionActorQueuedCommands";
        public static final String HEAD_WAIT = "connectionActorHeadWait";
        public static final String ACTIVE_DURATION = "connectionActorActiveDuration";
        public static final String ABORT_DURATION = "connectionActorAbortDuration";
        public static final String PENDING_ABORT_CHILD = "connectionActorPendingAbortChild";
    }

    private final LongUpDownCounter queuedCommands;
    private final LongUpDownCounter headWait;
    private final DoubleHistogram activeDuration;
    private final DoubleHistogram abortDuration;
    private final LongUpDownCounter pendingAbortChild;

    public ConnectionActorMetrics(@NonNull Meter meter) {
        queuedCommands = meter.upDownCounterBuilder(MetricNames.QUEUED_COMMANDS)
            .setUnit("commands")
            .build();
        headWait = meter.upDownCounterBuilder(MetricNames.HEAD_WAIT)
            .setUnit("actors")
            .build();
        activeDuration = meter.histogramBuilder(MetricNames.ACTIVE_DURATION)
            .setUnit("ms")
            .build();
        abortDuration = meter.histogramBuilder(MetricNames.ABORT_DURATION)
            .setUnit("ms")
            .build();
        pendingAbortChild = meter.upDownCounterBuilder(MetricNames.PENDING_ABORT_CHILD)
            .setUnit("children")
            .build();
    }

    @Override
    public void queuedCommandsChanged(int delta) {
        queuedCommands.add(delta);
    }

    @Override
    public void headWaitChanged(@NonNull ConnectionActor.HeadWaitReason reason, int delta) {
        headWait.add(delta, Attributes.of(REASON_ATTRIBUTE, reason.metricLabel()));
    }

    @Override
    public void activeDuration(@NonNull Duration duration) {
        activeDuration.record(duration.toNanos() / 1_000_000.0);
    }

    @Override
    public void abortDuration(@NonNull Duration duration) {
        abortDuration.record(duration.toNanos() / 1_000_000.0);
    }

    @Override
    public void pendingAbortChildChanged(@NonNull ConnectionActor.AbortChild child, int delta) {
        pendingAbortChild.add(delta, Attributes.of(CHILD_ATTRIBUTE, child.metricLabel()));
    }
}
