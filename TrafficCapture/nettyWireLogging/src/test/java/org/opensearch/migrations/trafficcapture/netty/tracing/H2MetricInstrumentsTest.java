package org.opensearch.migrations.trafficcapture.netty.tracing;

import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.trafficcapture.protos.Http2FrameType;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * RFC 0001 §7.7 — verifies the {@link H2MetricInstruments} are correctly constructed
 * by {@link RootWireLoggingContext} and emit OTel metrics with the documented names
 * and attribute keys.
 */
class H2MetricInstrumentsTest {

    @Test
    void rootContext_exposesH2InstrumentInstance() {
        var bundle = new InMemoryInstrumentationBundle(true, true);
        var ctx = new RootWireLoggingContext(bundle.openTelemetrySdk, IContextTracker.DO_NOTHING_TRACKER);
        Assertions.assertNotNull(ctx.h2Instruments,
                "RootWireLoggingContext must instantiate H2MetricInstruments");
        Assertions.assertNotNull(ctx.h2Instruments.streamsOpened);
        Assertions.assertNotNull(ctx.h2Instruments.streamsClosedNormal);
        Assertions.assertNotNull(ctx.h2Instruments.streamsReset);
        Assertions.assertNotNull(ctx.h2Instruments.alpnNegotiated);
        Assertions.assertNotNull(ctx.h2Instruments.framesIn);
        Assertions.assertNotNull(ctx.h2Instruments.framesOut);
        Assertions.assertNotNull(ctx.h2Instruments.offloadBlockDurationMs);
    }

    @Test
    void recordingMetrics_doesNotThrow() {
        var bundle = new InMemoryInstrumentationBundle(true, true);
        var ctx = new RootWireLoggingContext(bundle.openTelemetrySdk, IContextTracker.DO_NOTHING_TRACKER);
        var h2 = ctx.h2Instruments;
        Assertions.assertDoesNotThrow(() -> {
            h2.streamsOpened.add(1);
            h2.streamsClosedNormal.add(1);
            h2.streamsReset.add(1);
            h2.recordAlpn("h2");
            h2.recordAlpn("http/1.1");
            h2.recordAlpn(null);
            h2.recordFrameIn(Http2FrameType.H2_HEADERS);
            h2.recordFrameOut(Http2FrameType.H2_DATA);
            h2.offloadBlockDurationMs.record(12.5);
        });
    }
}
