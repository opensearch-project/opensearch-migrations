package org.opensearch.migrations.trafficcapture.netty.tracing;

import org.opensearch.migrations.trafficcapture.protos.Http2FrameType;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;

/**
 * RFC 0001 §7.7 — OpenTelemetry counters / histograms for the HTTP/2 capture path.
 *
 * <p>One instance per {@link RootWireLoggingContext}. Wire by passing the {@code Meter}
 * obtained from the OTel SDK; the context already does this for the H1 metrics.
 *
 * <p>Counter / histogram names follow the {@code h2.streams.*} / {@code h2.frames.*} /
 * {@code h2.alpn.*} / {@code h2.offload.*} families per the LLD.
 */
public class H2MetricInstruments {

    public static final String SCOPE = "h2";

    public final LongCounter streamsOpened;
    public final LongCounter streamsClosedNormal;
    public final LongCounter streamsReset;

    public final LongCounter alpnNegotiated;        // attribute: protocol={h2|http/1.1|none}

    public final LongCounter framesIn;              // attribute: type={data,headers,...}
    public final LongCounter framesOut;             // attribute: type={data,headers,...}

    public final DoubleHistogram offloadBlockDurationMs;

    public H2MetricInstruments(Meter meter) {
        this.streamsOpened = meter.counterBuilder("h2.streams.opened")
                .setDescription("HTTP/2 streams created (HEADERS observed on a new streamId)")
                .build();
        this.streamsClosedNormal = meter.counterBuilder("h2.streams.closed.normal")
                .setDescription("HTTP/2 streams closed normally (END_STREAM both directions)")
                .build();
        this.streamsReset = meter.counterBuilder("h2.streams.reset")
                .setDescription("HTTP/2 streams terminated by RST_STREAM")
                .build();
        this.alpnNegotiated = meter.counterBuilder("h2.alpn.negotiated")
                .setDescription("ALPN selections by protocol (attribute: protocol)")
                .build();
        this.framesIn = meter.counterBuilder("h2.frames.in")
                .setDescription("HTTP/2 frames observed in the inbound (client->proxy) direction")
                .build();
        this.framesOut = meter.counterBuilder("h2.frames.out")
                .setDescription("HTTP/2 frames observed in the outbound (proxy->client) direction")
                .build();
        this.offloadBlockDurationMs = meter.histogramBuilder("h2.offload.block_duration_ms")
                .setDescription("Per-stream offload-block hold time in milliseconds")
                .setUnit("ms")
                .build();
    }

    /** Convenience helpers for wiring into handlers without deep OTel-attribute knowledge. */
    public void recordFrameIn(Http2FrameType type) {
        framesIn.add(1, io.opentelemetry.api.common.Attributes.of(
                io.opentelemetry.api.common.AttributeKey.stringKey("type"), type.name()));
    }

    public void recordFrameOut(Http2FrameType type) {
        framesOut.add(1, io.opentelemetry.api.common.Attributes.of(
                io.opentelemetry.api.common.AttributeKey.stringKey("type"), type.name()));
    }

    public void recordAlpn(String protocol) {
        alpnNegotiated.add(1, io.opentelemetry.api.common.Attributes.of(
                io.opentelemetry.api.common.AttributeKey.stringKey("protocol"),
                protocol == null || protocol.isEmpty() ? "none" : protocol));
    }
}
