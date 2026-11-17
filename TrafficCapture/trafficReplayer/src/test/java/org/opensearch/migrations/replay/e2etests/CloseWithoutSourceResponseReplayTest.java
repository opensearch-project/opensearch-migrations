/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.e2etests;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;
import org.opensearch.migrations.transform.TransformationLoader;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The capture shape that pinned production commit heads: the source never answered a request, and the
 * proxy closed the connection when its timeout fired. The request's record must still finish -- its tuple
 * records the missing source response -- and the partition must commit past both records.
 */
@Tag("longTest")
class CloseWithoutSourceResponseReplayTest extends FullTrafficReplayerTest {
    private static final String BULK = "POST /_bulk HTTP/1.1\r\nHost: source\r\nContent-Length: 2\r\n\r\n{}";
    private static final String OK = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";

    @Test
    void requestWithNoSourceResponseFinishesWhenTheCapturedCloseArrives() throws Exception {
        var records = List.of(
            stream(0, List.of(read(0, 0, BULK), endOfMessage(1, 1))),
            stream(1, List.of(close(2, 61)))
        );
        assertReplaysAndCommits(records, 1);
    }

    @Test
    void trailingUnansweredRequestAfterAnsweredOneFinishesAtClose() throws Exception {
        var records = List.of(
            stream(0, List.of(read(0, 0, BULK), endOfMessage(1, 1), write(2, 2, OK), read(3, 3, BULK), endOfMessage(4, 4))),
            stream(1, List.of(close(5, 64)))
        );
        assertReplaysAndCommits(records, 2);
    }

    private void assertReplaysAndCommits(List<CaptureRecord> records, int expectedTuples) throws Exception {
        try (var server = SimpleNettyHttpServer.makeServer(
            false,
            request -> new SimpleHttpResponse(
                Map.of(HttpHeaderNames.CONTENT_LENGTH.toString(), "2"),
                "OK".getBytes(StandardCharsets.UTF_8),
                "OK",
                200
            )
        )) {
            var result = replay(
                server.localhostEndpoint(),
                records,
                () -> new TransformationLoader().getTransformerFactoryLoaderWithNewHostName("localhost"),
                null,
                new RootReplayerContext(io.opentelemetry.api.OpenTelemetry.noop()),
                expectedTuples
            );
            Assertions.assertEquals(expectedTuples, result.tuples().size());
            Assertions.assertTrue(result.fatalFailures().isEmpty(), () -> "fatal: " + result.fatalFailures());
            Assertions.assertEquals(
                (long) records.size(),
                result.committedOffsets().get(TOPIC_PARTITION).offset(),
                "the partition must commit past the unanswered request's record and the close"
            );
        }
    }

    private static CaptureRecord stream(int number, List<TrafficObservation> observations) {
        return CaptureRecord.newBuilder().setTrafficStream(
            TrafficStream.newBuilder()
                .setNodeId("writer")
                .setConnectionId("connection")
                .setNumber(number)
                .addAllSubStream(observations)
        ).build();
    }

    private static TrafficObservation read(long sequence, long seconds, String data) {
        return at(sequence, seconds).setRead(ReadObservation.newBuilder().setData(ByteString.copyFromUtf8(data))).build();
    }

    private static TrafficObservation write(long sequence, long seconds, String data) {
        return at(sequence, seconds).setWrite(WriteObservation.newBuilder().setData(ByteString.copyFromUtf8(data))).build();
    }

    private static TrafficObservation endOfMessage(long sequence, long seconds) {
        return at(sequence, seconds).setEndOfMessageIndicator(EndOfMessageIndication.getDefaultInstance()).build();
    }

    private static TrafficObservation close(long sequence, long seconds) {
        return at(sequence, seconds).setClose(CloseObservation.getDefaultInstance()).build();
    }

    private static TrafficObservation.Builder at(long sequence, long seconds) {
        return TrafficObservation.newBuilder()
            .setTs(Timestamp.newBuilder().setSeconds(seconds))
            .setConnectionObservationSequence(sequence);
    }
}
