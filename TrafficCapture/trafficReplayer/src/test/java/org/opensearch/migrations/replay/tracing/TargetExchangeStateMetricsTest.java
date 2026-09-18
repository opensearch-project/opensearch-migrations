package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.replay.lifecycle.TargetExchangeState;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;

import io.opentelemetry.api.common.AttributeKey;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TargetExchangeStateMetricsTest extends InstrumentationTest {

    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withAllTracking();
    }

    @Test
    void recordsPhaseAndChannelStateAsAttributedUpDownCounters() {
        var metrics = rootContext.getTargetExchangeStateMetrics();
        metrics.phaseChanged(TargetExchangeState.Phase.SENDING_REQUEST, 1);
        metrics.phaseChanged(TargetExchangeState.Phase.RETRY_DELAY, 2);
        metrics.channelStateChanged(TargetExchangeState.ChannelState.ACTIVE, 1);
        metrics.channelStateChanged(TargetExchangeState.ChannelState.CLOSING, 2);

        assertPoint(
            TargetExchangeStateMetrics.MetricNames.ACTIVE_PHASE,
            TargetExchangeStateMetrics.PHASE_ATTRIBUTE,
            TargetExchangeState.Phase.SENDING_REQUEST.metricLabel(),
            1
        );
        assertPoint(
            TargetExchangeStateMetrics.MetricNames.ACTIVE_PHASE,
            TargetExchangeStateMetrics.PHASE_ATTRIBUTE,
            TargetExchangeState.Phase.RETRY_DELAY.metricLabel(),
            2
        );
        assertPoint(
            TargetExchangeStateMetrics.MetricNames.CHANNEL_STATE,
            TargetExchangeStateMetrics.STATE_ATTRIBUTE,
            TargetExchangeState.ChannelState.ACTIVE.metricLabel(),
            1
        );
        assertPoint(
            TargetExchangeStateMetrics.MetricNames.CHANNEL_STATE,
            TargetExchangeStateMetrics.STATE_ATTRIBUTE,
            TargetExchangeState.ChannelState.CLOSING.metricLabel(),
            2
        );

        metrics.phaseChanged(TargetExchangeState.Phase.SENDING_REQUEST, -1);
        metrics.phaseChanged(TargetExchangeState.Phase.RETRY_DELAY, -2);
        metrics.channelStateChanged(TargetExchangeState.ChannelState.ACTIVE, -1);
        metrics.channelStateChanged(TargetExchangeState.ChannelState.CLOSING, -2);

        assertPoint(
            TargetExchangeStateMetrics.MetricNames.ACTIVE_PHASE,
            TargetExchangeStateMetrics.PHASE_ATTRIBUTE,
            TargetExchangeState.Phase.SENDING_REQUEST.metricLabel(),
            0
        );
        assertPoint(
            TargetExchangeStateMetrics.MetricNames.ACTIVE_PHASE,
            TargetExchangeStateMetrics.PHASE_ATTRIBUTE,
            TargetExchangeState.Phase.RETRY_DELAY.metricLabel(),
            0
        );
        assertPoint(
            TargetExchangeStateMetrics.MetricNames.CHANNEL_STATE,
            TargetExchangeStateMetrics.STATE_ATTRIBUTE,
            TargetExchangeState.ChannelState.ACTIVE.metricLabel(),
            0
        );
        assertPoint(
            TargetExchangeStateMetrics.MetricNames.CHANNEL_STATE,
            TargetExchangeStateMetrics.STATE_ATTRIBUTE,
            TargetExchangeState.ChannelState.CLOSING.metricLabel(),
            0
        );
    }

    private void assertPoint(
        String metricName,
        AttributeKey<String> attribute,
        String attributeValue,
        long expectedValue
    ) {
        var point = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics()
            .stream()
            .filter(metric -> metric.getName().equals(metricName))
            .findFirst()
            .orElseThrow()
            .getLongSumData()
            .getPoints()
            .stream()
            .filter(candidate -> attributeValue.equals(candidate.getAttributes().get(attribute)))
            .findFirst()
            .orElseThrow();
        Assertions.assertEquals(expectedValue, point.getValue());
    }
}
