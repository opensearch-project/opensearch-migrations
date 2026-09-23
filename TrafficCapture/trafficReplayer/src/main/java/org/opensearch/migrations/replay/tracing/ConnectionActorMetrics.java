package org.opensearch.migrations.replay.tracing;

// REBUILD-LIMBO(G2) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: TargetConnectionOwner . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G2)
/*

import java.time.Duration;

import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

public final class ConnectionActorMetrics implements TargetConnectionOwner.Metrics {
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
    public void headWaitChanged(@NonNull TargetConnectionOwner.HeadWaitReason reason, int delta) {
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
    public void pendingAbortChildChanged(@NonNull TargetConnectionOwner.AbortChild child, int delta) {
        pendingAbortChild.add(delta, Attributes.of(CHILD_ATTRIBUTE, child.metricLabel()));
    }

}

*/
// REBUILD-LIMBO-END(G2)