/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;

import io.opentelemetry.api.OpenTelemetry;
import lombok.NonNull;

// REBUILD-LIMBO-NOTE(G3): this whole class is deleted; its three instrument fields move to
// RootReplayerContext, which already declares them under these names.
/**
 * Root instrumentation scope holding the Kafka source's metric instruments.
 *
 * <p>Scaffolding, removed in G3. {@code RootReplayerContext} is the real home for these fields and already
 * declares {@code pollInstruments}, {@code commitInstruments} and {@code kafkaCommitInstruments} — but it
 * aggregates instruments for about thirty contexts across the whole replayer, and is typed on
 * {@code ISourceTrafficChannelKey} and {@code ITrafficStreamKey}, so promoting it means promoting that entire
 * chain. This holds the same three fields under the same names so the contexts do not change shape when they
 * move; G3 deletes this class and repoints them at {@code RootReplayerContext}.
 */
public class KafkaSourceRootContext extends RootOtelContext {

    public static final String SCOPE_NAME = IKafkaConsumerContexts.ScopeNames.KAFKA_CONSUMER_SCOPE;

    public final KafkaConsumerContexts.PollScopeContext.MetricInstruments pollInstruments;
    public final KafkaConsumerContexts.CommitScopeContext.MetricInstruments commitInstruments;
    public final KafkaConsumerContexts.KafkaCommitScopeContext.MetricInstruments kafkaCommitInstruments;
    public final KafkaConsumerContexts.RebalanceCallbackScopeContext.MetricInstruments
        rebalanceCallbackInstruments;

    /** Tracks active contexts the way the rest of the replayer does, rather than leaving a caller to pass none. */
    public KafkaSourceRootContext(@NonNull OpenTelemetry sdk) {
        this(
            sdk,
            new CompositeContextTracker(new ActiveContextTracker(), new ActiveContextTrackerByActivityType())
        );
    }

    public KafkaSourceRootContext(@NonNull OpenTelemetry sdk, @NonNull IContextTracker contextTracker) {
        super(SCOPE_NAME, contextTracker, sdk);
        var meter = getMeterProvider().get(SCOPE_NAME);
        pollInstruments = KafkaConsumerContexts.PollScopeContext.makeMetrics(meter);
        commitInstruments = KafkaConsumerContexts.CommitScopeContext.makeMetrics(meter);
        kafkaCommitInstruments = KafkaConsumerContexts.KafkaCommitScopeContext.makeMetrics(meter);
        rebalanceCallbackInstruments =
            KafkaConsumerContexts.RebalanceCallbackScopeContext.makeMetrics(meter);
    }

    public IKafkaConsumerContexts.IPollScopeContext createPollContext() {
        return new KafkaConsumerContexts.PollScopeContext(this, null);
    }

    public IKafkaConsumerContexts.ICommitScopeContext createCommitContext() {
        return new KafkaConsumerContexts.CommitScopeContext(this, null);
    }

    public IKafkaConsumerContexts.IRebalanceCallbackScopeContext createRebalanceCallbackContext() {
        return new KafkaConsumerContexts.RebalanceCallbackScopeContext(this);
    }
}
