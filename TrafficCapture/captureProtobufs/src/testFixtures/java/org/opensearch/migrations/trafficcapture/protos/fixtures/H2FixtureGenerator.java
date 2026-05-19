package org.opensearch.migrations.trafficcapture.protos.fixtures;

import java.util.List;
import java.util.Map;

import org.opensearch.migrations.trafficcapture.protos.AlpnNegotiationObservation;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.Http2DataPayload;
import org.opensearch.migrations.trafficcapture.protos.Http2FrameObservation;
import org.opensearch.migrations.trafficcapture.protos.Http2FrameType;
import org.opensearch.migrations.trafficcapture.protos.Http2GoAwayPayload;
import org.opensearch.migrations.trafficcapture.protos.Http2HeaderField;
import org.opensearch.migrations.trafficcapture.protos.Http2HeadersPayload;
import org.opensearch.migrations.trafficcapture.protos.Http2RstStreamPayload;
import org.opensearch.migrations.trafficcapture.protos.Http2SettingsPayload;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;

/**
 * RFC 0001 §11 — programmatic fixture generator for HTTP/2 capture scenarios. Builds
 * deterministic {@link TrafficStream} protos so downstream tests can exercise the
 * accumulator + adapter without booting a real proxy + H2 client.
 *
 * <p>Each factory method produces a fully-populated {@code TrafficStream} with
 * {@code captureFormatVersion="v2"} and {@code negotiatedAlpn="h2"}. Timestamps are
 * fixed to a known epoch second so the resulting bytes are byte-stable across runs.
 */
public final class H2FixtureGenerator {

    public static final long FIXED_TS_SECONDS = 1700000000L;

    private H2FixtureGenerator() {}

    public static Timestamp ts() {
        return Timestamp.newBuilder().setSeconds(FIXED_TS_SECONDS).build();
    }

    public static Http2HeaderField hf(String name, String value) {
        return Http2HeaderField.newBuilder()
                .setName(ByteString.copyFromUtf8(name))
                .setValue(ByteString.copyFromUtf8(value))
                .build();
    }

    public static TrafficObservation alpn() {
        return TrafficObservation.newBuilder().setTs(ts())
                .setAlpn(AlpnNegotiationObservation.newBuilder()
                        .setNegotiatedProtocol("h2")
                        .setOfferedByClient("h2,http/1.1").build())
                .build();
    }

    public static TrafficObservation settings(boolean ack, Map<Integer, Integer> values) {
        var b = Http2SettingsPayload.newBuilder().setAck(ack);
        if (values != null) values.forEach(b::putSettings);
        return TrafficObservation.newBuilder().setTs(ts())
                .setHttp2Frame(Http2FrameObservation.newBuilder()
                        .setStreamId(0).setType(Http2FrameType.H2_SETTINGS)
                        .setSettings(b.build()).build())
                .build();
    }

    public static TrafficObservation headers(int streamId, boolean endStream,
                                              List<Http2HeaderField> fields) {
        var hp = Http2HeadersPayload.newBuilder()
                .setEndStream(endStream).setEndHeaders(true);
        for (var f : fields) hp.addFields(f);
        return TrafficObservation.newBuilder().setTs(ts())
                .setHttp2Frame(Http2FrameObservation.newBuilder()
                        .setStreamId(streamId).setType(Http2FrameType.H2_HEADERS)
                        .setHeaders(hp.build()).build())
                .build();
    }

    public static TrafficObservation data(int streamId, boolean endStream, byte[] body) {
        return TrafficObservation.newBuilder().setTs(ts())
                .setHttp2Frame(Http2FrameObservation.newBuilder()
                        .setStreamId(streamId).setType(Http2FrameType.H2_DATA)
                        .setData(Http2DataPayload.newBuilder()
                                .setData(ByteString.copyFrom(body))
                                .setEndStream(endStream).build())
                        .build())
                .build();
    }

    public static TrafficObservation rstStream(int streamId, int errorCode) {
        return TrafficObservation.newBuilder().setTs(ts())
                .setHttp2Frame(Http2FrameObservation.newBuilder()
                        .setStreamId(streamId).setType(Http2FrameType.H2_RST_STREAM)
                        .setRstStream(Http2RstStreamPayload.newBuilder()
                                .setErrorCode(errorCode).build())
                        .build())
                .build();
    }

    public static TrafficObservation goAway(int lastStreamId, int errorCode) {
        return TrafficObservation.newBuilder().setTs(ts())
                .setHttp2Frame(Http2FrameObservation.newBuilder()
                        .setStreamId(0).setType(Http2FrameType.H2_GOAWAY)
                        .setGoAway(Http2GoAwayPayload.newBuilder()
                                .setLastStreamId(lastStreamId)
                                .setErrorCode(errorCode).build())
                        .build())
                .build();
    }

    public static TrafficObservation close() {
        return TrafficObservation.newBuilder().setTs(ts())
                .setClose(CloseObservation.getDefaultInstance())
                .build();
    }

    /** Builder helper. */
    public static TrafficStream.Builder h2Stream(String connectionId) {
        return TrafficStream.newBuilder()
                .setConnectionId(connectionId)
                .setNodeId("fixture-node")
                .setCaptureFormatVersion("v2")
                .setNegotiatedAlpn("h2");
    }

    /** Fixture: single-stream POST /_bulk with a small body. */
    public static TrafficStream bulkIndexSingleStream() {
        return h2Stream("h2-bulk-single")
                .addSubStream(alpn())
                .addSubStream(settings(false, Map.of(1, 4096, 4, 65535)))
                .addSubStream(headers(1, false, List.of(
                        hf(":method", "POST"),
                        hf(":path", "/_bulk"),
                        hf(":scheme", "https"),
                        hf(":authority", "localhost:9200"))))
                .addSubStream(data(1, true, "{\"index\":{}}\n{\"f\":1}\n".getBytes()))
                .addSubStream(headers(1, false, List.of(hf(":status", "200"))))
                .addSubStream(data(1, true, "{\"errors\":false}".getBytes()))
                .addSubStream(close())
                .setNumberOfThisLastChunk(1)
                .build();
    }

    /** Fixture: 5 concurrent GETs multiplexed on one connection (streamIds 1, 3, 5, 7, 9). */
    public static TrafficStream searchMultiStream() {
        var b = h2Stream("h2-search-multi")
                .addSubStream(alpn())
                .addSubStream(settings(false, Map.of()));
        for (int sid = 1; sid <= 9; sid += 2) {
            b.addSubStream(headers(sid, true, List.of(
                    hf(":method", "GET"),
                    hf(":path", "/_search"),
                    hf(":scheme", "https"),
                    hf(":authority", "localhost"))));
        }
        for (int sid = 1; sid <= 9; sid += 2) {
            b.addSubStream(headers(sid, false, List.of(hf(":status", "200"))));
            b.addSubStream(data(sid, true, ("{\"hits\":" + sid + "}").getBytes()));
        }
        return b.addSubStream(close()).setNumberOfThisLastChunk(1).build();
    }

    /** Fixture: POST that gets RST_STREAM mid-body. Produces no full RRP, RESET_BY_PEER status. */
    public static TrafficStream bulkWithRstStream() {
        return h2Stream("h2-bulk-rst")
                .addSubStream(alpn())
                .addSubStream(headers(1, false, List.of(
                        hf(":method", "POST"),
                        hf(":path", "/_bulk"),
                        hf(":scheme", "https"),
                        hf(":authority", "localhost"))))
                .addSubStream(data(1, false, "partial body".getBytes()))
                .addSubStream(rstStream(1, 8 /* CANCEL */))
                .addSubStream(close())
                .setNumberOfThisLastChunk(1)
                .build();
    }

    /** Fixture: GOAWAY mid-flight. Stream 1 completes, stream 3 is orphaned. */
    public static TrafficStream goAwayMidFlight() {
        return h2Stream("h2-goaway")
                .addSubStream(alpn())
                .addSubStream(headers(1, true, List.of(
                        hf(":method", "GET"), hf(":path", "/"), hf(":authority", "h"))))
                .addSubStream(headers(3, true, List.of(
                        hf(":method", "GET"), hf(":path", "/"), hf(":authority", "h"))))
                .addSubStream(headers(1, false, List.of(hf(":status", "200"))))
                .addSubStream(data(1, true, "ok".getBytes()))
                .addSubStream(goAway(1 /* lastStreamId */, 0 /* NO_ERROR */))
                // Stream 3 is orphaned (id > lastStreamId), no response observation.
                .addSubStream(close())
                .setNumberOfThisLastChunk(1)
                .build();
    }
}
