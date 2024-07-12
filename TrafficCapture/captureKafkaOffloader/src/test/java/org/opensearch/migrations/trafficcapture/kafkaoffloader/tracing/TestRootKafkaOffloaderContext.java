package org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing;

import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.tracing.RootOtelContext;
import org.opensearch.migrations.trafficcapture.tracing.ConnectionContext;
import org.opensearch.migrations.trafficcapture.tracing.IRootOffloaderContext;

import lombok.Getter;

public class TestRootKafkaOffloaderContext extends RootOtelContext
    implements
        IRootOffloaderContext,
        IRootKafkaOffloaderContext {
    @Getter
    public final KafkaRecordContext.MetricInstruments kafkaOffloadingInstruments;
    @Getter
    public final ConnectionContext.MetricInstruments connectionInstruments;

    private final InMemoryInstrumentationBundle inMemoryInstrumentationBundle;

    public static TestRootKafkaOffloaderContext withTracking() {
        return new TestRootKafkaOffloaderContext(new InMemoryInstrumentationBundle(true, true));
    }

    public static TestRootKafkaOffloaderContext noTracking() {
        return new TestRootKafkaOffloaderContext();
    }

    public TestRootKafkaOffloaderContext() {
        this(new InMemoryInstrumentationBundle(null, null));
    }

    public TestRootKafkaOffloaderContext(InMemoryInstrumentationBundle inMemoryInstrumentationBundle) {
        super(
            "tests",
            DO_NOTHING_TRACKER,
            inMemoryInstrumentationBundle == null ? null : inMemoryInstrumentationBundle.openTelemetrySdk
        );
        this.inMemoryInstrumentationBundle = inMemoryInstrumentationBundle;
        final var meter = getMeterProvider().get("test");
        this.kafkaOffloadingInstruments = KafkaRecordContext.makeMetrics(meter);
        this.connectionInstruments = ConnectionContext.makeMetrics(meter);
    }
}
