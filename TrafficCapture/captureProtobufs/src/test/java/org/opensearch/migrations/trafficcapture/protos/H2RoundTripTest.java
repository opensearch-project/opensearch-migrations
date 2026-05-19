package org.opensearch.migrations.trafficcapture.protos;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip serialization of every HTTP/2 observation variant introduced by RFC 0001.
 * Each test builds a populated proto message, encodes to bytes, decodes back, and asserts
 * field-level equality. Protects against accidental tag reuse, type changes, or oneof
 * regressions in TrafficCaptureStream.proto.
 */
class H2RoundTripTest {

    private static <T extends com.google.protobuf.Message> T roundTrip(T message, com.google.protobuf.Parser<T> parser)
            throws Exception {
        byte[] bytes = message.toByteArray();
        return parser.parseFrom(bytes);
    }

    @Test
    void http2HeaderField_roundTrip() throws Exception {
        var original = Http2HeaderField.newBuilder()
                .setName(ByteString.copyFromUtf8(":method"))
                .setValue(ByteString.copyFromUtf8("POST"))
                .setSensitive(false)
                .build();
        var decoded = roundTrip(original, Http2HeaderField.parser());
        assertEquals(original, decoded);
    }

    @Test
    void http2HeadersPayload_roundTrip() throws Exception {
        var original = Http2HeadersPayload.newBuilder()
                .addFields(Http2HeaderField.newBuilder()
                        .setName(ByteString.copyFromUtf8(":method"))
                        .setValue(ByteString.copyFromUtf8("GET")))
                .addFields(Http2HeaderField.newBuilder()
                        .setName(ByteString.copyFromUtf8(":path"))
                        .setValue(ByteString.copyFromUtf8("/_search")))
                .setEndStream(true)
                .setEndHeaders(true)
                .setDependsOnStreamId(0)
                .setWeight(15)
                .setExclusive(false)
                .build();
        var decoded = roundTrip(original, Http2HeadersPayload.parser());
        assertEquals(original, decoded);
        assertEquals(2, decoded.getFieldsCount());
        assertTrue(decoded.getEndStream());
    }

    @Test
    void http2DataPayload_roundTrip() throws Exception {
        var original = Http2DataPayload.newBuilder()
                .setData(ByteString.copyFromUtf8("hello"))
                .setEndStream(true)
                .setPadLength(0)
                .build();
        var decoded = roundTrip(original, Http2DataPayload.parser());
        assertEquals(original, decoded);
        assertEquals("hello", decoded.getData().toStringUtf8());
    }

    @Test
    void http2SettingsPayload_roundTrip() throws Exception {
        var original = Http2SettingsPayload.newBuilder()
                .setAck(false)
                .putSettings(1, 4096)            // SETTINGS_HEADER_TABLE_SIZE
                .putSettings(3, 100)             // SETTINGS_MAX_CONCURRENT_STREAMS
                .putSettings(4, 65535)           // SETTINGS_INITIAL_WINDOW_SIZE
                .build();
        var decoded = roundTrip(original, Http2SettingsPayload.parser());
        assertEquals(original, decoded);
        assertEquals(3, decoded.getSettingsMap().size());
        assertEquals(4096, decoded.getSettingsMap().get(1));
    }

    @Test
    void http2SettingsAck_roundTrip() throws Exception {
        var original = Http2SettingsPayload.newBuilder().setAck(true).build();
        var decoded = roundTrip(original, Http2SettingsPayload.parser());
        assertEquals(original, decoded);
        assertTrue(decoded.getAck());
        assertTrue(decoded.getSettingsMap().isEmpty());
    }

    @Test
    void http2WindowUpdatePayload_roundTrip() throws Exception {
        var original = Http2WindowUpdatePayload.newBuilder().setIncrement(65535).build();
        var decoded = roundTrip(original, Http2WindowUpdatePayload.parser());
        assertEquals(original, decoded);
    }

    @Test
    void http2RstStreamPayload_roundTrip() throws Exception {
        // 0x8 == CANCEL per RFC 7540 §7
        var original = Http2RstStreamPayload.newBuilder().setErrorCode(0x8).build();
        var decoded = roundTrip(original, Http2RstStreamPayload.parser());
        assertEquals(original, decoded);
    }

    @Test
    void http2GoAwayPayload_roundTrip() throws Exception {
        var original = Http2GoAwayPayload.newBuilder()
                .setLastStreamId(7)
                .setErrorCode(0)                                // NO_ERROR
                .setDebugData(ByteString.copyFromUtf8("graceful shutdown"))
                .build();
        var decoded = roundTrip(original, Http2GoAwayPayload.parser());
        assertEquals(original, decoded);
        assertEquals(7, decoded.getLastStreamId());
    }

    @Test
    void http2PingPayload_roundTrip() throws Exception {
        byte[] eightBytes = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        var original = Http2PingPayload.newBuilder()
                .setOpaqueData(ByteString.copyFrom(eightBytes))
                .setAck(false)
                .build();
        var decoded = roundTrip(original, Http2PingPayload.parser());
        assertEquals(original, decoded);
    }

    @Test
    void http2PushPromisePayload_roundTrip() throws Exception {
        var original = Http2PushPromisePayload.newBuilder()
                .setPromisedStreamId(2)
                .addFields(Http2HeaderField.newBuilder()
                        .setName(ByteString.copyFromUtf8(":path"))
                        .setValue(ByteString.copyFromUtf8("/promoted")))
                .build();
        var decoded = roundTrip(original, Http2PushPromisePayload.parser());
        assertEquals(original, decoded);
    }

    @Test
    void http2PriorityPayload_roundTrip() throws Exception {
        var original = Http2PriorityPayload.newBuilder()
                .setDependsOnStreamId(3)
                .setWeight(15)
                .setExclusive(true)
                .build();
        var decoded = roundTrip(original, Http2PriorityPayload.parser());
        assertEquals(original, decoded);
    }

    @Test
    void http2ContinuationPayload_roundTrip() throws Exception {
        var original = Http2ContinuationPayload.newBuilder()
                .setHeaderBlockFragment(ByteString.copyFrom(new byte[]{0x40, 0x05}))
                .setEndHeaders(true)
                .build();
        var decoded = roundTrip(original, Http2ContinuationPayload.parser());
        assertEquals(original, decoded);
    }

    @Test
    void http2FrameObservation_headersVariant_roundTrip() throws Exception {
        var original = Http2FrameObservation.newBuilder()
                .setStreamId(1)
                .setType(Http2FrameType.H2_HEADERS)
                .setFlags(0x05)                          // END_STREAM | END_HEADERS
                .setRawFrame(ByteString.copyFrom(new byte[]{0, 0, 5, 1, 5, 0, 0, 0, 1, 0x40, 0x01, 0x40, 0x01, 0x40}))
                .setHeaders(Http2HeadersPayload.newBuilder()
                        .addFields(Http2HeaderField.newBuilder()
                                .setName(ByteString.copyFromUtf8(":method"))
                                .setValue(ByteString.copyFromUtf8("GET")))
                        .setEndStream(true)
                        .setEndHeaders(true))
                .build();
        var decoded = roundTrip(original, Http2FrameObservation.parser());
        assertEquals(original, decoded);
        assertEquals(Http2FrameObservation.PayloadCase.HEADERS, decoded.getPayloadCase());
        assertEquals(1, decoded.getStreamId());
        assertFalse(decoded.getTruncated());
    }

    @Test
    void http2FrameObservation_truncatedVariant_roundTrip() throws Exception {
        var original = Http2FrameObservation.newBuilder()
                .setStreamId(13)
                .setType(Http2FrameType.H2_DATA)
                .setFlags(0)
                .setRawFrame(ByteString.copyFrom(new byte[9]))
                .setTruncated(true)
                .build();
        var decoded = roundTrip(original, Http2FrameObservation.parser());
        assertEquals(original, decoded);
        assertTrue(decoded.getTruncated());
        assertEquals(Http2FrameObservation.PayloadCase.PAYLOAD_NOT_SET, decoded.getPayloadCase());
    }

    @Test
    void alpnNegotiationObservation_roundTrip() throws Exception {
        var original = AlpnNegotiationObservation.newBuilder()
                .setNegotiatedProtocol("h2")
                .setOfferedByClient("h2,http/1.1")
                .build();
        var decoded = roundTrip(original, AlpnNegotiationObservation.parser());
        assertEquals(original, decoded);
    }

    @Test
    void trafficObservation_h2FrameOneofArm_roundTrip() throws Exception {
        var inner = Http2FrameObservation.newBuilder()
                .setStreamId(7)
                .setType(Http2FrameType.H2_RST_STREAM)
                .setFlags(0)
                .setRstStream(Http2RstStreamPayload.newBuilder().setErrorCode(0x8))
                .build();
        var original = TrafficObservation.newBuilder()
                .setTs(Timestamp.newBuilder().setSeconds(1700000000L).setNanos(0))
                .setHttp2Frame(inner)
                .build();
        var decoded = roundTrip(original, TrafficObservation.parser());
        assertEquals(original, decoded);
        assertEquals(TrafficObservation.CaptureCase.HTTP2FRAME, decoded.getCaptureCase());
    }

    @Test
    void trafficObservation_alpnOneofArm_roundTrip() throws Exception {
        var original = TrafficObservation.newBuilder()
                .setTs(Timestamp.newBuilder().setSeconds(1700000000L))
                .setAlpn(AlpnNegotiationObservation.newBuilder()
                        .setNegotiatedProtocol("http/1.1")
                        .setOfferedByClient("http/1.1"))
                .build();
        var decoded = roundTrip(original, TrafficObservation.parser());
        assertEquals(original, decoded);
        assertEquals(TrafficObservation.CaptureCase.ALPN, decoded.getCaptureCase());
    }

    @Test
    void trafficStream_v2EnvelopeFields_roundTrip() throws Exception {
        var original = TrafficStream.newBuilder()
                .setConnectionId("conn-1")
                .setNodeId("node-1")
                .setNumber(0)
                .setCaptureFormatVersion("v2")
                .setNegotiatedAlpn("h2")
                .build();
        var decoded = roundTrip(original, TrafficStream.parser());
        assertEquals(original, decoded);
        assertEquals("v2", decoded.getCaptureFormatVersion());
        assertEquals("h2", decoded.getNegotiatedAlpn());
    }

    @Test
    void trafficStream_v1EnvelopeDefaults_roundTrip() throws Exception {
        // Mimic a v1 capture: no captureFormatVersion or negotiatedAlpn set.
        // Proto3 string defaults to empty; both fields must round-trip as empty.
        var original = TrafficStream.newBuilder()
                .setConnectionId("conn-old")
                .setNodeId("node-old")
                .setNumber(0)
                .build();
        var decoded = roundTrip(original, TrafficStream.parser());
        assertEquals(original, decoded);
        assertEquals("", decoded.getCaptureFormatVersion());
        assertEquals("", decoded.getNegotiatedAlpn());
    }

    @Test
    void trafficStream_h2SubStreamSequence_roundTrip() throws Exception {
        // Realistic ordering: ALPN, then SETTINGS, then HEADERS+END_STREAM, then RST_STREAM.
        var ts = Timestamp.newBuilder().setSeconds(1700000000L).build();
        var stream = TrafficStream.newBuilder()
                .setConnectionId("c1")
                .setNodeId("n1")
                .setNumber(0)
                .setCaptureFormatVersion("v2")
                .setNegotiatedAlpn("h2")
                .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                        .setAlpn(AlpnNegotiationObservation.newBuilder()
                                .setNegotiatedProtocol("h2")
                                .setOfferedByClient("h2,http/1.1")))
                .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                        .setHttp2Frame(Http2FrameObservation.newBuilder()
                                .setStreamId(0)
                                .setType(Http2FrameType.H2_SETTINGS)
                                .setSettings(Http2SettingsPayload.newBuilder()
                                        .putSettings(1, 4096))))
                .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                        .setHttp2Frame(Http2FrameObservation.newBuilder()
                                .setStreamId(1)
                                .setType(Http2FrameType.H2_HEADERS)
                                .setFlags(0x5)
                                .setHeaders(Http2HeadersPayload.newBuilder()
                                        .addFields(Http2HeaderField.newBuilder()
                                                .setName(ByteString.copyFromUtf8(":method"))
                                                .setValue(ByteString.copyFromUtf8("GET")))
                                        .setEndStream(true)
                                        .setEndHeaders(true))))
                .build();
        var decoded = roundTrip(stream, TrafficStream.parser());
        assertEquals(stream, decoded);
        assertEquals(3, decoded.getSubStreamCount());
        assertEquals(TrafficObservation.CaptureCase.ALPN, decoded.getSubStream(0).getCaptureCase());
        assertEquals(TrafficObservation.CaptureCase.HTTP2FRAME, decoded.getSubStream(1).getCaptureCase());
    }
}
