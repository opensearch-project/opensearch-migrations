/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafka;

import java.nio.charset.StandardCharsets;

import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.ConnectObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.ReadSegmentObservation;
import org.opensearch.migrations.trafficcapture.protos.RequestIntentionallyDropped;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;
import org.opensearch.migrations.trafficcapture.protos.WriteSegmentObservation;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Slf4j
class TrafficStreamDumperTest {

    private static Timestamp ts(long epochSeconds) {
        return Timestamp.newBuilder().setSeconds(epochSeconds).build();
    }

    private static TrafficObservation readObs(long epochSeconds, String data) {
        return TrafficObservation.newBuilder()
            .setTs(ts(epochSeconds))
            .setRead(ReadObservation.newBuilder()
                .setData(ByteString.copyFrom(data, StandardCharsets.UTF_8)))
            .build();
    }

    private static TrafficObservation readSegmentObs(long epochSeconds, String data) {
        return TrafficObservation.newBuilder()
            .setTs(ts(epochSeconds))
            .setReadSegment(ReadSegmentObservation.newBuilder()
                .setData(ByteString.copyFrom(data, StandardCharsets.UTF_8)))
            .build();
    }

    private static TrafficObservation writeObs(long epochSeconds, String data) {
        return TrafficObservation.newBuilder()
            .setTs(ts(epochSeconds))
            .setWrite(WriteObservation.newBuilder()
                .setData(ByteString.copyFrom(data, StandardCharsets.UTF_8)))
            .build();
    }

    private static TrafficObservation writeSegmentObs(long epochSeconds, String data) {
        return TrafficObservation.newBuilder()
            .setTs(ts(epochSeconds))
            .setWriteSegment(WriteSegmentObservation.newBuilder()
                .setData(ByteString.copyFrom(data, StandardCharsets.UTF_8)))
            .build();
    }

    private static TrafficObservation connectObs(long epochSeconds) {
        return TrafficObservation.newBuilder()
            .setTs(ts(epochSeconds))
            .setConnect(ConnectObservation.newBuilder())
            .build();
    }

    private static TrafficObservation closeObs(long epochSeconds) {
        return TrafficObservation.newBuilder()
            .setTs(ts(epochSeconds))
            .setClose(CloseObservation.newBuilder())
            .build();
    }

    private static TrafficObservation eomObs(long epochSeconds) {
        return TrafficObservation.newBuilder()
            .setTs(ts(epochSeconds))
            .setEndOfMessageIndicator(EndOfMessageIndication.newBuilder())
            .build();
    }

    private static TrafficObservation droppedObs(long epochSeconds) {
        return TrafficObservation.newBuilder()
            .setTs(ts(epochSeconds))
            .setRequestDropped(RequestIntentionallyDropped.newBuilder())
            .build();
    }

    @Test
    void testFullRequestResponseCycle() {
        var ts = TrafficStream.newBuilder()
            .setNodeId("node1").setConnectionId("conn123").setNumber(5)
            .addSubStream(connectObs(100))
            .addSubStream(readObs(101, "GET /cat/indices HTTP/1.1\r\nHost: localhost\r\n\r\n"))
            .addSubStream(eomObs(102))
            .addSubStream(writeObs(103, "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"))
            .addSubStream(closeObs(104))
            .build();

        var result = TrafficStreamDumper.format(ts, 0, 1234, 64, 64);
        log.info("dump-raw output: {}", result);

        Assertions.assertTrue(result.startsWith("[100-104]"));
        Assertions.assertTrue(result.contains("p:0 o:  1234"));
        Assertions.assertTrue(result.contains("ncs:node1.conn123.5:"));
        Assertions.assertTrue(result.contains("OPEN"));
        Assertions.assertTrue(result.contains("R["));
        Assertions.assertTrue(result.contains("GET /cat/indices"));
        Assertions.assertTrue(result.contains("EOM"));
        Assertions.assertTrue(result.contains("W["));
        Assertions.assertTrue(result.contains("HTTP/1.1 200 OK"));
        Assertions.assertTrue(result.contains("CLOSE"));
    }

    @Test
    void testConsecutiveReadsCoalesced() {
        var ts = TrafficStream.newBuilder()
            .setNodeId("n").setConnectionId("c").setNumber(0)
            .addSubStream(readObs(100, "AAAA"))       // 4 bytes
            .addSubStream(readObs(101, "BBBBBBBBBB"))  // 10 bytes
            .addSubStream(readSegmentObs(102, "CC"))   // 2 bytes
            .build();

        var result = TrafficStreamDumper.format(ts, -1, -1, 64, 64);
        log.info("coalesced reads: {}", result);

        // All three should be coalesced into one R[16]
        Assertions.assertTrue(result.contains("R[16]"));
        Assertions.assertTrue(result.contains("AAAABBBBBBBBBBCC"));
        // Should have exactly one R[ token
        Assertions.assertEquals(1, countOccurrences(result, "R["));
    }

    @Test
    void testConsecutiveWritesCoalesced() {
        var ts = TrafficStream.newBuilder()
            .setNodeId("n").setConnectionId("c").setNumber(0)
            .addSubStream(writeObs(100, "XXXX"))
            .addSubStream(writeSegmentObs(101, "YY"))
            .build();

        var result = TrafficStreamDumper.format(ts, 2, 99, 64, 64);
        log.info("coalesced writes: {}", result);

        Assertions.assertTrue(result.contains("W[6]"));
        Assertions.assertTrue(result.contains("XXXXYY"));
    }

    @Test
    void testReadWriteNotCoalesced() {
        var ts = TrafficStream.newBuilder()
            .setNodeId("n").setConnectionId("c").setNumber(0)
            .addSubStream(readObs(100, "REQ"))
            .addSubStream(writeObs(101, "RSP"))
            .build();

        var result = TrafficStreamDumper.format(ts, -1, -1, 64, 64);
        log.info("read then write: {}", result);

        Assertions.assertTrue(result.contains("R[3]: REQ"));
        Assertions.assertTrue(result.contains("W[3]: RSP"));
    }

    @Test
    void testPreviewTruncation() {
        var longData = "GET /very/long/path/that/exceeds/preview HTTP/1.1\r\nHost: x\r\n\r\n";
        var ts = TrafficStream.newBuilder()
            .setNodeId("n").setConnectionId("c").setNumber(0)
            .addSubStream(readObs(100, longData))
            .build();

        var result = TrafficStreamDumper.format(ts, -1, -1, 10, 10);
        log.info("truncated preview: {}", result);

        // Preview should be 10 chars + "..."
        Assertions.assertTrue(result.contains("R[" + longData.length() + "]: GET /very/..."));
    }

    @Test
    void testNoKafkaMetadataForFileInput() {
        var ts = TrafficStream.newBuilder()
            .setNodeId("n").setConnectionId("c").setNumber(0)
            .addSubStream(connectObs(100))
            .build();

        var result = TrafficStreamDumper.format(ts, -1, -1, 64, 64);
        log.info("file input (no kafka): {}", result);

        Assertions.assertFalse(result.contains("p:"));
        Assertions.assertFalse(result.contains("o:"));
    }

    @Test
    void testDroppedToken() {
        var ts = TrafficStream.newBuilder()
            .setNodeId("n").setConnectionId("c").setNumber(0)
            .addSubStream(readObs(100, "GET / HTTP/1.1\r\n\r\n"))
            .addSubStream(droppedObs(101))
            .build();

        var result = TrafficStreamDumper.format(ts, -1, -1, 64, 64);
        Assertions.assertTrue(result.contains("DROPPED"));
    }

    @Test
    void testEmptyTrafficStream() {
        var ts = TrafficStream.newBuilder()
            .setNodeId("n").setConnectionId("c").setNumber(0)
            .build();

        var result = TrafficStreamDumper.format(ts, 0, 0, 64, 64);
        Assertions.assertTrue(result.contains("[?-?]"));
        Assertions.assertTrue(result.contains("ncs:n.c.0:"));
    }

    @Test
    void testPreviewWithZeroBytes() {
        var ts = TrafficStream.newBuilder()
            .setNodeId("n").setConnectionId("c").setNumber(0)
            .addSubStream(readObs(100, "GET / HTTP/1.1\r\n\r\n"))
            .build();

        var result = TrafficStreamDumper.format(ts, -1, -1, 0, 0);
        log.info("zero preview: {}", result);

        // Should have R[size] but no preview text after it
        Assertions.assertTrue(result.contains("R[18]"));
        Assertions.assertFalse(result.contains("GET"));
    }

    private static int countOccurrences(String str, String sub) {
        int count = 0, idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) { count++; idx += sub.length(); }
        return count;
    }
}
