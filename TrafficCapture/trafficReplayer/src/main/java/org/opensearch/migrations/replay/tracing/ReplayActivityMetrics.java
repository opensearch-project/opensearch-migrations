/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry;
import org.opensearch.migrations.replay.util.ContextActivityCollector;
import org.opensearch.migrations.replay.util.KafkaDiagnosticCollector;
import org.opensearch.migrations.replay.util.OwnerOperationCollector;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongGauge;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

/**
 * Periodic bounded gauges for the context, owner-obligation, and Kafka diagnostic populations.
 */
public final class ReplayActivityMetrics {
    public static final AttributeKey<String> ACTIVITY_ATTRIBUTE = AttributeKey.stringKey("activity");
    public static final AttributeKey<String> OPERATION_TYPE_ATTRIBUTE =
        AttributeKey.stringKey("operationType");
    private static final String CONTEXTS_UNIT = "contexts";
    private static final String OPERATIONS_UNIT = "operations";

    public static final class MetricNames {
        private MetricNames() {}

        public static final String ACTIVE_CONTEXTS = "replayActiveContexts";
        public static final String OLDEST_CONTEXT_AGE = "replayOldestContextAge";
        public static final String ACTIVE_CONTEXTS_BY_ACTIVITY = "replayActiveContextsByActivity";
        public static final String OLDEST_CONTEXT_AGE_BY_ACTIVITY =
            "replayOldestContextAgeByActivity";
        public static final String OVER_THRESHOLD_CONTEXTS_BY_ACTIVITY =
            "replayOverThresholdContextsByActivity";
        public static final String ACTIVE_OWNER_OPERATIONS = "replayActiveOwnerOperations";
        public static final String LONG_RUNNING_OWNER_OPERATIONS =
            "replayLongRunningOwnerOperations";
        public static final String ACTIVE_OWNER_OPERATIONS_BY_TYPE =
            "replayActiveOwnerOperationsByType";
        public static final String LONG_RUNNING_OWNER_OPERATIONS_BY_TYPE =
            "replayLongRunningOwnerOperationsByType";
        public static final String OLDEST_KAFKA_RECORD_AGE = "replayOldestKafkaRecordAge";
        public static final String KAFKA_COMMIT_UNCERTAIN_PARTITIONS =
            "replayKafkaCommitUncertainPartitions";
    }

    private final LongGauge activeContexts;
    private final LongGauge oldestContextAge;
    private final LongGauge activeContextsByActivity;
    private final LongGauge oldestContextAgeByActivity;
    private final LongGauge overThresholdContextsByActivity;
    private final LongGauge activeOwnerOperations;
    private final LongGauge longRunningOwnerOperations;
    private final LongGauge activeOwnerOperationsByType;
    private final LongGauge longRunningOwnerOperationsByType;
    private final LongGauge oldestKafkaRecordAge;
    private final LongGauge kafkaCommitUncertainPartitions;

    public ReplayActivityMetrics(@NonNull Meter meter) {
        activeContexts = meter.gaugeBuilder(MetricNames.ACTIVE_CONTEXTS)
            .ofLongs()
            .setDescription("Open replay contexts across all activity types")
            .setUnit(CONTEXTS_UNIT)
            .build();
        oldestContextAge = meter.gaugeBuilder(MetricNames.OLDEST_CONTEXT_AGE)
            .ofLongs()
            .setDescription("Age of the globally oldest open replay context")
            .setUnit("ms")
            .build();
        activeContextsByActivity = meter.gaugeBuilder(MetricNames.ACTIVE_CONTEXTS_BY_ACTIVITY)
            .ofLongs()
            .setDescription("Open replay contexts by bounded activity type")
            .setUnit(CONTEXTS_UNIT)
            .build();
        oldestContextAgeByActivity = meter.gaugeBuilder(MetricNames.OLDEST_CONTEXT_AGE_BY_ACTIVITY)
            .ofLongs()
            .setDescription("Age of the oldest open replay context by bounded activity type")
            .setUnit("ms")
            .build();
        overThresholdContextsByActivity =
            meter.gaugeBuilder(MetricNames.OVER_THRESHOLD_CONTEXTS_BY_ACTIVITY)
                .ofLongs()
                .setDescription("Open replay contexts older than the activity threshold")
                .setUnit(CONTEXTS_UNIT)
                .build();
        activeOwnerOperations = meter.gaugeBuilder(MetricNames.ACTIVE_OWNER_OPERATIONS)
            .ofLongs()
            .setDescription("Outstanding owner-local operations across replay owners")
            .setUnit(OPERATIONS_UNIT)
            .build();
        longRunningOwnerOperations = meter.gaugeBuilder(MetricNames.LONG_RUNNING_OWNER_OPERATIONS)
            .ofLongs()
            .setDescription("Outstanding owner-local operations older than the reporting threshold")
            .setUnit(OPERATIONS_UNIT)
            .build();
        activeOwnerOperationsByType =
            meter.gaugeBuilder(MetricNames.ACTIVE_OWNER_OPERATIONS_BY_TYPE)
                .ofLongs()
                .setDescription("Outstanding owner-local operations by bounded operation type")
                .setUnit(OPERATIONS_UNIT)
                .build();
        longRunningOwnerOperationsByType =
            meter.gaugeBuilder(MetricNames.LONG_RUNNING_OWNER_OPERATIONS_BY_TYPE)
                .ofLongs()
                .setDescription(
                    "Outstanding owner-local operations older than the reporting threshold by bounded operation type"
                )
                .setUnit(OPERATIONS_UNIT)
                .build();
        oldestKafkaRecordAge = meter.gaugeBuilder(MetricNames.OLDEST_KAFKA_RECORD_AGE)
            .ofLongs()
            .setDescription("Age of the globally oldest open Kafka record context")
            .setUnit("ms")
            .build();
        kafkaCommitUncertainPartitions =
            meter.gaugeBuilder(MetricNames.KAFKA_COMMIT_UNCERTAIN_PARTITIONS)
                .ofLongs()
                .setDescription("Owned Kafka partitions with an unresolved or unknown commit outcome")
                .setUnit("partitions")
                .build();
    }

    public void recordSample(
        @NonNull ContextActivityCollector.Snapshot contexts,
        @NonNull OwnerOperationCollector.Snapshot ownerOperations,
        @NonNull KafkaDiagnosticCollector.Snapshot kafka
    ) {
        activeContexts.set(contexts.totalActiveContexts());
        oldestContextAge.set(contexts.globallyOldestAge().toMillis());
        contexts.activities().values().forEach(activity -> {
            var attributes = Attributes.of(ACTIVITY_ATTRIBUTE, activity.activity());
            activeContextsByActivity.set(activity.activeCount(), attributes);
            oldestContextAgeByActivity.set(activity.oldestAge().toMillis(), attributes);
            overThresholdContextsByActivity.set(activity.overThresholdCount(), attributes);
        });
        activeOwnerOperations.set(ownerOperations.activeCount());
        longRunningOwnerOperations.set(ownerOperations.longRunningCount());
        for (var operationType : OutstandingOperationRegistry.OperationType.values()) {
            var attributes = Attributes.of(OPERATION_TYPE_ATTRIBUTE, operationType.name());
            activeOwnerOperationsByType.set(
                ownerOperations.activeByOperationType().getOrDefault(operationType, 0),
                attributes
            );
            longRunningOwnerOperationsByType.set(
                ownerOperations.longRunningByOperationType().getOrDefault(operationType, 0),
                attributes
            );
        }
        oldestKafkaRecordAge.set(
            contexts.oldestKafkaRecord().map(oldestRecord -> oldestRecord.age().toMillis()).orElse(0L)
        );
        kafkaCommitUncertainPartitions.set(kafka.uncertainPartitionCount());
    }
}
