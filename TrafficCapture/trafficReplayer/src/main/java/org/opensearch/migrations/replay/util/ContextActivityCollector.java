/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.util;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.tracing.IKafkaConsumerContexts;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.KafkaConsumerContexts;
import org.opensearch.migrations.replay.tracing.ReplayContexts;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

import lombok.NonNull;

/**
 * Reads the two active-context indexes without mutating a context or adding child collections to it.
 */
public final class ContextActivityCollector {
    public static final Duration DEFAULT_AGE_THRESHOLD = Duration.ofSeconds(30);
    public static final Duration SCHEDULED_AGE_THRESHOLD = Duration.ofMinutes(2);
    public static final int MAX_OLD_CONTEXT_DETAILS = 16;
    public static final int MAX_DESCENDANT_DETAILS = 16;

    // The warnings target compiler-generated members of this private activity descriptor.
    @SuppressWarnings({"java:S100", "java:S1172", "java:S1186"})
    private record KnownActivity(
        Class<? extends IScopedInstrumentationAttributes> type,
        String name,
        Duration threshold
    ) {}

    // The warnings target compiler-generated members of this public diagnostic contract.
    @SuppressWarnings({"java:S100", "java:S1172", "java:S1186"})
    public record ActivitySummary(
        @NonNull String activity,
        long activeCount,
        @NonNull Duration oldestAge,
        long overThresholdCount,
        @NonNull Duration threshold
    ) {}

    // The warnings target compiler-generated members of this public diagnostic contract.
    @SuppressWarnings({"java:S100", "java:S1186"})
    public record ContextIdentity(
        @NonNull Optional<KafkaRecordId> kafkaRecordId,
        @NonNull Optional<ConnectionProcessingId> connectionProcessingId,
        @NonNull Optional<ReplayRequestId> replayRequestId
    ) {}

    // The warnings target compiler-generated members of this public diagnostic contract.
    @SuppressWarnings({"java:S100", "java:S1186"})
    public record ContextDetail(
        @NonNull String activity,
        @NonNull Duration age,
        @NonNull List<String> ancestorChain,
        @NonNull ContextIdentity identity
    ) {}

    // The warnings target compiler-generated members of this public diagnostic contract.
    @SuppressWarnings({"java:S100", "java:S1172", "java:S1186"})
    public record OldestKafkaRecord(
        @NonNull KafkaRecordId recordId,
        @NonNull Duration age,
        int descendantCount,
        @NonNull List<ContextDetail> descendants
    ) {}

    // The warnings target compiler-generated members of this public diagnostic contract.
    @SuppressWarnings({"java:S100", "java:S1172", "java:S1186"})
    public record Snapshot(
        @NonNull Instant observedAt,
        long totalActiveContexts,
        @NonNull Duration globallyOldestAge,
        @NonNull Map<String, ActivitySummary> activities,
        @NonNull List<ContextDetail> oldContexts,
        long oldContextCount,
        @NonNull Optional<OldestKafkaRecord> oldestKafkaRecord
    ) {}

    private static final List<KnownActivity> KNOWN_ACTIVITIES = List.of(
        known(ReplayContexts.ConnectionContext.class, IReplayContexts.ActivityNames.CHANNEL),
        known(ReplayContexts.SocketContext.class, IReplayContexts.ActivityNames.TCP_CONNECTION),
        known(ReplayContexts.KafkaRecordContext.class, IReplayContexts.ActivityNames.RECORD_LIFETIME),
        known(
            ReplayContexts.TrafficStreamLifecycleContext.class,
            IReplayContexts.ActivityNames.TRAFFIC_STREAM_LIFETIME
        ),
        known(ReplayContexts.HttpTransactionContext.class, IReplayContexts.ActivityNames.HTTP_TRANSACTION),
        known(
            ReplayContexts.RequestAccumulationContext.class,
            IReplayContexts.ActivityNames.ACCUMULATING_REQUEST
        ),
        known(
            ReplayContexts.ResponseAccumulationContext.class,
            IReplayContexts.ActivityNames.ACCUMULATING_RESPONSE
        ),
        known(
            ReplayContexts.RequestTransformationContext.class,
            IReplayContexts.ActivityNames.TRANSFORMATION
        ),
        known(
            ReplayContexts.ScheduledContext.class,
            IReplayContexts.ActivityNames.SCHEDULED,
            SCHEDULED_AGE_THRESHOLD
        ),
        known(
            ReplayContexts.TargetRequestContext.class,
            IReplayContexts.ActivityNames.TARGET_TRANSACTION
        ),
        known(
            ReplayContexts.RequestConnectingContext.class,
            IReplayContexts.ActivityNames.REQUEST_CONNECTING
        ),
        known(ReplayContexts.RequestSendingContext.class, IReplayContexts.ActivityNames.REQUEST_SENDING),
        known(
            ReplayContexts.WaitingForHttpResponseContext.class,
            IReplayContexts.ActivityNames.WAITING_FOR_RESPONSE
        ),
        known(
            ReplayContexts.ReceivingHttpResponseContext.class,
            IReplayContexts.ActivityNames.RECEIVING_RESPONSE
        ),
        known(ReplayContexts.TupleHandlingContext.class, IReplayContexts.ActivityNames.TUPLE_COMPARISON),
        known(
            KafkaConsumerContexts.PollScopeContext.class,
            IKafkaConsumerContexts.ActivityNames.KAFKA_POLL
        ),
        known(
            KafkaConsumerContexts.CommitScopeContext.class,
            IKafkaConsumerContexts.ActivityNames.COMMIT
        ),
        known(
            KafkaConsumerContexts.KafkaCommitScopeContext.class,
            IKafkaConsumerContexts.ActivityNames.KAFKA_COMMIT
        ),
        known(
            KafkaConsumerContexts.RebalanceCallbackScopeContext.class,
            IKafkaConsumerContexts.ActivityNames.REBALANCE_CALLBACK
        )
    );

    private static final Map<Class<?>, Duration> THRESHOLD_BY_TYPE = KNOWN_ACTIVITIES.stream()
        .collect(Collectors.toUnmodifiableMap(KnownActivity::type, KnownActivity::threshold));

    /** Contexts younger than this cannot be over any threshold, so old-context scans stop at the first one. */
    private static final Duration MINIMUM_AGE_THRESHOLD = KNOWN_ACTIVITIES.stream()
        .map(KnownActivity::threshold)
        .reduce(DEFAULT_AGE_THRESHOLD, (a, b) -> a.compareTo(b) <= 0 ? a : b);

    private final Clock clock;
    private final ActiveContextTracker globalTracker;
    private final ActiveContextTrackerByActivityType byActivityTracker;

    public ContextActivityCollector(
        @NonNull Clock clock,
        @NonNull ActiveContextTracker globalTracker,
        @NonNull ActiveContextTrackerByActivityType byActivityTracker
    ) {
        this.clock = clock;
        this.globalTracker = globalTracker;
        this.byActivityTracker = byActivityTracker;
    }

    public Snapshot collect() {
        return collect(true);
    }

    /**
     * Both indexes are ordered by start time, so contexts at or over an age threshold are always a prefix of
     * their index. Every scan below stops at the first context younger than its threshold rather than copying
     * or aging the whole index, which holds every in-flight Kafka record and all of its descendants.
     *
     * @param includeOldestRecordDescendants whether to find the oldest record's descendants, which needs an
     *                                       ancestor walk from every active context; only the diagnostic log
     *                                       reports them
     */
    public Snapshot collect(boolean includeOldestRecordDescendants) {
        var now = clock.instant();
        var activities = new LinkedHashMap<String, ActivitySummary>();
        KNOWN_ACTIVITIES.forEach(known -> activities.put(known.name(), summarizeActivity(known, now)));

        var oldContextScan = scanOldContexts(now);

        var oldestRecord = globalTracker.getActiveScopesByAge()
            .filter(IReplayContexts.IKafkaRecordContext.class::isInstance)
            .map(IReplayContexts.IKafkaRecordContext.class::cast)
            .findFirst()
            .map(recordContext -> describeOldestKafkaRecord(recordContext, now, includeOldestRecordDescendants));

        return new Snapshot(
            now,
            globalTracker.size(),
            oldContextScan.globallyOldest() == null ? Duration.ZERO : age(oldContextScan.globallyOldest(), now),
            Map.copyOf(activities),
            List.copyOf(oldContextScan.oldContexts()),
            oldContextScan.oldContextCount(),
            oldestRecord
        );
    }

    private ActivitySummary summarizeActivity(KnownActivity known, Instant now) {
        var cutoff = now.minus(known.threshold());
        long activeCount = 0;
        long overThreshold = 0;
        var oldestAge = Duration.ZERO;
        var stillOverThreshold = true;
        for (var iterator = byActivityTracker.getOldestActiveScopes(scopeClass(known.type())).iterator();
             iterator.hasNext();) {
            var context = iterator.next();
            if (activeCount++ == 0) {
                oldestAge = age(context, now);
            }
            if (stillOverThreshold) {
                stillOverThreshold = !context.getStartTimeInstant().isAfter(cutoff);
                if (stillOverThreshold) {
                    overThreshold++;
                }
            }
        }
        return new ActivitySummary(known.name(), activeCount, oldestAge, overThreshold, known.threshold());
    }

    // The warnings target compiler-generated members of this private scan result.
    @SuppressWarnings({"java:S100", "java:S1172", "java:S1186"})
    private record OldContextScan(
        IScopedInstrumentationAttributes globallyOldest,
        List<ContextDetail> oldContexts,
        long oldContextCount
    ) {}

    private OldContextScan scanOldContexts(Instant now) {
        var oldContexts = new ArrayList<ContextDetail>();
        long oldContextCount = 0;
        var youngestPossiblyOld = now.minus(MINIMUM_AGE_THRESHOLD);
        IScopedInstrumentationAttributes globallyOldest = null;
        for (var iterator = globalTracker.getActiveScopesByAge().iterator(); iterator.hasNext();) {
            var context = iterator.next();
            if (globallyOldest == null) {
                globallyOldest = context;
            }
            if (context.getStartTimeInstant().isAfter(youngestPossiblyOld)) {
                break;
            }
            if (age(context, now).compareTo(thresholdFor(context)) >= 0) {
                oldContextCount++;
                if (oldContexts.size() < MAX_OLD_CONTEXT_DETAILS) {
                    oldContexts.add(detail(context, now));
                }
            }
        }
        return new OldContextScan(globallyOldest, oldContexts, oldContextCount);
    }

    private OldestKafkaRecord describeOldestKafkaRecord(
        IReplayContexts.IKafkaRecordContext recordContext,
        Instant now,
        boolean includeDescendants
    ) {
        if (!includeDescendants) {
            return new OldestKafkaRecord(recordContext.getRecordId(), age(recordContext, now), 0, List.of());
        }
        var descendants = globalTracker.getActiveScopesByAge()
            .filter(context -> context != recordContext)
            .filter(context -> hasAncestor(context, recordContext))
            .toList();
        return new OldestKafkaRecord(
            recordContext.getRecordId(),
            age(recordContext, now),
            descendants.size(),
            descendants.stream()
                .limit(MAX_DESCENDANT_DETAILS)
                .map(context -> detail(context, now))
                .toList()
        );
    }

    private static boolean hasAncestor(
        IScopedInstrumentationAttributes context,
        IScopedInstrumentationAttributes ancestor
    ) {
        var enclosing = context.getEnclosingScope();
        while (enclosing != null) {
            if (enclosing == ancestor) {
                return true;
            }
            enclosing = enclosing.getEnclosingScope();
        }
        return false;
    }

    private static ContextDetail detail(IScopedInstrumentationAttributes context, Instant now) {
        var chain = new ArrayList<String>();
        IScopedInstrumentationAttributes current = context;
        while (current != null) {
            chain.add(0, current.getActivityName());
            current = current.getEnclosingScope();
        }
        return new ContextDetail(
            context.getActivityName(),
            age(context, now),
            List.copyOf(chain),
            identity(context)
        );
    }

    private static ContextIdentity identity(IScopedInstrumentationAttributes context) {
        KafkaRecordId recordId = null;
        ConnectionProcessingId connectionId = null;
        ReplayRequestId requestId = null;
        IScopedInstrumentationAttributes current = context;
        while (current != null) {
            if (recordId == null && current instanceof IReplayContexts.IKafkaRecordContext recordContext) {
                recordId = recordContext.getRecordId();
            }
            if (connectionId == null && current instanceof IReplayContexts.IConnectionContext connection) {
                connectionId = connection.getConnectionProcessingId();
            }
            if (requestId == null && current instanceof IReplayContexts.IRequestContext request) {
                requestId = request.getRequestId();
                connectionId = request.getConnectionProcessingId();
            }
            current = current.getEnclosingScope();
        }
        return new ContextIdentity(
            Optional.ofNullable(recordId),
            Optional.ofNullable(connectionId),
            Optional.ofNullable(requestId)
        );
    }

    private static Duration thresholdFor(IScopedInstrumentationAttributes context) {
        return THRESHOLD_BY_TYPE.getOrDefault(context.getClass(), DEFAULT_AGE_THRESHOLD);
    }

    private static Duration age(IScopedInstrumentationAttributes context, Instant now) {
        var duration = Duration.between(context.getStartTimeInstant(), now);
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    private static KnownActivity known(
        Class<? extends IScopedInstrumentationAttributes> type,
        String name
    ) {
        return known(type, name, DEFAULT_AGE_THRESHOLD);
    }

    private static KnownActivity known(
        Class<? extends IScopedInstrumentationAttributes> type,
        String name,
        Duration threshold
    ) {
        return new KnownActivity(type, name, threshold);
    }

    @SuppressWarnings("unchecked")
    private static Class<IScopedInstrumentationAttributes> scopeClass(
        Class<? extends IScopedInstrumentationAttributes> type
    ) {
        return (Class<IScopedInstrumentationAttributes>) type;
    }
}
