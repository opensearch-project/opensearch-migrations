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

import org.opensearch.migrations.replay.datahandlers.TargetConnectionLimiter;
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
 * A target connect that the process's connection limiter delays still produces its tuple once the
 * reserved slot arrives, and the limiter records both the delay and the successful connect.
 */
@Tag("longTest")
class DelayedTargetConnectReplayTest extends FullTrafficReplayerTest {
    private static final String BULK = "POST /_bulk HTTP/1.1\r\nHost: source\r\nContent-Length: 2\r\n\r\n{}";
    private static final String OK = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";

    @Test
    void aConnectDelayedByTheLimiterStillReplaysTheRequest() throws Exception {
        // A frozen limiter clock keeps the spent allowance from refilling during test setup.
        var limiter = new TargetConnectionLimiter(10, () -> 0L);
        // At 10 per second the allowance holds one connect plus the current slot; spend both.
        Assertions.assertEquals(0, limiter.reserveConnect().delayNanos());
        Assertions.assertEquals(0, limiter.reserveConnect().delayNanos());
        var records = List.of(
            stream(0, List.of(read(0, 0, BULK), endOfMessage(1, 1), write(2, 2, OK), close(3, 3)))
        );
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
                1,
                limiter
            );
            Assertions.assertEquals(1, result.tuples().size());
            Assertions.assertTrue(result.fatalFailures().isEmpty(), () -> "fatal: " + result.fatalFailures());
            Assertions.assertEquals(1, limiter.totalDelayedReservations());
            Assertions.assertEquals(0, limiter.totalRateDecreases());
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
