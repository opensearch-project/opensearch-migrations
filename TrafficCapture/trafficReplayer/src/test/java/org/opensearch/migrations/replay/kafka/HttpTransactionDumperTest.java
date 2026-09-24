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
import java.time.Instant;

import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.identity.CapturedConnectionId;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;

import org.apache.kafka.common.TopicPartition;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Slf4j
class HttpTransactionDumperTest {

    private static final ConnectionProcessingId CONNECTION = new ConnectionProcessingId(
        new PartitionGenerationId(new TopicPartition("traffic", 0), 3),
        new CapturedConnectionId("node1", "conn1"),
        2
    );

    @Test
    void testCompleteRequestResponse() {
        var baos = new ByteArrayOutputStream();
        var dumper = new HttpTransactionDumper(new PrintStream(baos));
        var request = new HttpMessageAndTimestamp.Request(Instant.ofEpochSecond(100));
        request.add("GET /_cat/indices HTTP/1.1\r\nHost: localhost\r\n\r\n"
            .getBytes(StandardCharsets.UTF_8));
        request.setLastPacketTimestamp(Instant.ofEpochSecond(101));
        var response = new HttpMessageAndTimestamp.Response(Instant.ofEpochSecond(102));
        response.add("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
            .getBytes(StandardCharsets.UTF_8));
        response.setLastPacketTimestamp(Instant.ofEpochSecond(103));
        var requestId = new ReplayRequestId(CONNECTION, 7);

        dumper.onRequestReconstituted(
            requestId,
            7,
            request,
            Instant.ofEpochSecond(100),
            Instant.ofEpochSecond(101),
            1_000L
        );
        dumper.onSourceResponseComplete(requestId, response, true);
        dumper.onCapturedClose(CONNECTION, Instant.ofEpochSecond(104));

        var output = baos.toString(StandardCharsets.UTF_8);
        log.info("dump-http output:\n{}", output);

        var lines = output.strip().split("\n");
        Assertions.assertEquals(3, lines.length, "REQ, RSP, and CLOSED must each be visible");

        boolean hasReq = false, hasRsp = false, hasClose = false;
        for (var line : lines) {
            if (line.contains("REQ")) {
                hasReq = true;
                Assertions.assertTrue(line.contains("GET /_cat/indices HTTP/1.1"));
                Assertions.assertTrue(line.contains("nc:node1.conn1:"));
                Assertions.assertTrue(line.contains("p:0"), "partition value");
                Assertions.assertTrue(line.contains("o:      "), "a transaction has no single Kafka offset");
            }
            if (line.contains("RSP")) {
                hasRsp = true;
                Assertions.assertTrue(line.contains("HTTP/1.1 200 OK"));
            }
            if (line.contains("CLOSED")) {
                hasClose = true;
            }
        }
        Assertions.assertTrue(hasReq, "Missing REQ line");
        Assertions.assertTrue(hasRsp, "Missing RSP line");
        Assertions.assertTrue(hasClose, "Missing CLOSED line");
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
