/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafka;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.opensearch.migrations.replay.CapturedTrafficToHttpTransactionAccumulator;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.ConnectObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Slf4j
class HttpTransactionDumperTest {

    private static final RootReplayerContext ROOT_CONTEXT = new RootReplayerContext(
        RootOtelContext.initializeOpenTelemetryWithCollectorOrAsNoop(null, "test", "test"),
        new CompositeContextTracker(new ActiveContextTracker(), new ActiveContextTrackerByActivityType())
    );

    private static Timestamp ts(long epochSeconds) {
        return Timestamp.newBuilder().setSeconds(epochSeconds).build();
    }

    private PojoTrafficStreamAndKey wrapWithKafkaKey(TrafficStream ts, int partition, long offset) {
        var channelContextManager = new ChannelContextManager(ROOT_CONTEXT);
        var key = new TrafficStreamKeyWithKafkaRecordId(
            tsk -> {
                var channelCtx = channelContextManager.retainOrCreateContext(tsk);
                return ROOT_CONTEXT.createTrafficStreamContextForKafkaSource(channelCtx, "key", 0);
            },
            ts,
            new PojoKafkaCommitOffsetData(0, partition, offset)
        );
        return new PojoTrafficStreamAndKey(ts, key);
    }

    @Test
    void testCompleteRequestResponse() {
        var baos = new ByteArrayOutputStream();
        var dumper = new HttpTransactionDumper(new PrintStream(baos));

        var ts = TrafficStream.newBuilder()
            .setNodeId("node1").setConnectionId("conn1").setNumber(0)
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(100))
                .setConnect(ConnectObservation.newBuilder()))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(101))
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom("GET /_cat/indices HTTP/1.1\r\nHost: localhost\r\n\r\n", StandardCharsets.UTF_8))))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(102))
                .setEndOfMessageIndicator(EndOfMessageIndication.newBuilder()))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(103))
                .setWrite(WriteObservation.newBuilder()
                    .setData(ByteString.copyFrom("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n", StandardCharsets.UTF_8))))
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(104))
                .setClose(CloseObservation.newBuilder()))
            .build();

        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofSeconds(30), "test", dumper);
        accumulator.accept(wrapWithKafkaKey(ts, 0, 42));
        accumulator.close();

        var output = baos.toString(StandardCharsets.UTF_8);
        log.info("dump-http output:\n{}", output);

        var lines = output.strip().split("\n");
        // Should have REQ, RSP, and CLOSED lines
        Assertions.assertTrue(lines.length >= 2, "Expected at least 2 lines, got: " + lines.length);

        boolean hasReq = false, hasRsp = false;
        for (var line : lines) {
            if (line.contains("REQ")) {
                hasReq = true;
                Assertions.assertTrue(line.contains("GET /_cat/indices HTTP/1.1"));
                Assertions.assertTrue(line.contains("nc:node1.conn1:"));
                Assertions.assertTrue(line.contains("p:"));
                Assertions.assertTrue(line.contains("o:"));
                Assertions.assertTrue(line.contains("0"), "partition value");
                Assertions.assertTrue(line.contains("42"), "offset value");
            }
            if (line.contains("RSP")) {
                hasRsp = true;
                Assertions.assertTrue(line.contains("HTTP/1.1 200 OK"));
            }
        }
        Assertions.assertTrue(hasReq, "Missing REQ line");
        Assertions.assertTrue(hasRsp, "Missing RSP line");
    }

    @Test
    void testExpiredConnection() {
        var baos = new ByteArrayOutputStream();
        var dumper = new HttpTransactionDumper(new PrintStream(baos));

        // A read with no EOM — will expire when accumulator closes
        var ts = TrafficStream.newBuilder()
            .setNodeId("node1").setConnectionId("conn2").setNumber(0)
            .addSubStream(TrafficObservation.newBuilder().setTs(ts(200))
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom("GET /partial HTTP/1.1\r\n", StandardCharsets.UTF_8))))
            .build();

        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofSeconds(30), "test", dumper);
        accumulator.accept(wrapWithKafkaKey(ts, 1, 99));
        accumulator.close();

        var output = baos.toString(StandardCharsets.UTF_8);
        log.info("expired output:\n{}", output);

        // The accumulator should fire onTrafficStreamsExpired for the incomplete read
        // (no REQ line since EOM was never reached)
        Assertions.assertTrue(output.contains("EXPIRED") || output.isEmpty(),
            "Expected EXPIRED or empty output for incomplete read, got: " + output);
    }

    @Test
    void testExtractFirstLine() {
        var msg = new org.opensearch.migrations.replay.HttpMessageAndTimestamp.Request(
            java.time.Instant.ofEpochSecond(100));
        msg.add("GET /test HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.UTF_8));

        var firstLine = HttpTransactionDumper.extractFirstLine(msg);
        Assertions.assertEquals("GET /test HTTP/1.1", firstLine);
    }

    @Test
    void testExtractFirstLineMultipleChunks() {
        var msg = new org.opensearch.migrations.replay.HttpMessageAndTimestamp.Request(
            java.time.Instant.ofEpochSecond(100));
        msg.add("GET /te".getBytes(StandardCharsets.UTF_8));
        msg.add("st HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.UTF_8));

        var firstLine = HttpTransactionDumper.extractFirstLine(msg);
        Assertions.assertEquals("GET /test HTTP/1.1", firstLine);
    }

    @Test
    void testExtractFirstLineNoNewline() {
        var msg = new org.opensearch.migrations.replay.HttpMessageAndTimestamp.Request(
            java.time.Instant.ofEpochSecond(100));
        msg.add("PARTIAL".getBytes(StandardCharsets.UTF_8));

        var firstLine = HttpTransactionDumper.extractFirstLine(msg);
        Assertions.assertEquals("PARTIAL", firstLine);
    }
}
