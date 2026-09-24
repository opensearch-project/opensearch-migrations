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

import org.opensearch.migrations.replay.TrafficReplayer;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * G3's exit evidence: source assembly reconstructs HTTP transactions from a topic written by the real,
 * unmodified capture proxy, and says so in output a person reads.
 *
 * <p>This is the cheapest complete observation of the milestone. Everything else about G3 is proved
 * deterministically against {@code RecordScript} as an oracle — which is stronger evidence about behaviour, and
 * no evidence at all about whether the pieces meet. Here the Kafka consumer, the record envelope, the
 * generation identities, {@code §7}'s apply order, {@code §9}'s assembly and the dumper all run in one process
 * against bytes the proxy actually produced.
 *
 * <p>Driven through {@code TrafficReplayer.main} rather than by constructing the owner, because an entry point
 * with no caller is what {@code AGENTS.md §4} refuses to count as wired.
 *
 * <p>One proxy and one topic for the whole class, shared deliberately. Each test used to start its own, and
 * three proxy containers racing for ports in one JVM failed to start rather than failing an assertion — an
 * infrastructure cost that says nothing about the code. One topic carrying all of the traffic also makes the
 * negative assertions stronger, since each mode is then shown to pick its own subject out of everything
 * available rather than out of a topic curated for it.
 *
 * <p>Requires Docker, hence {@code isolatedTest}.
 */
@Tag("isolatedTest")
class SourceAssemblyEvidenceTest {

    private static ProxyWrittenTopic supply;

    @BeforeAll
    static void captureTheTraffic() throws Exception {
        supply = ProxyWrittenTopic.start("g3-assembly-evidence");
        Assertions.assertEquals(200, supply.sendGet("/reconstruct-me"));
        // Waits for a durable TrafficStream rather than for any record: the proxy's startup capability probe
        // is a record, and gating on "at least one" would let the dumps run before the traffic arrived.
        Assertions.assertFalse(
            supply.readTrafficStreamValues(1).isEmpty(),
            "no captured traffic reached the topic"
        );
        supply.produceDirectly(0, truncatedTransaction().toByteArray());
    }

    @AfterAll
    static void releaseTheTopic() {
        if (supply != null) {
            supply.close();
        }
    }

    private static String dump(String mode) throws Exception {
        return captureStdout(() -> TrafficReplayer.main(new String[] {
            "--mode", mode,
            "--kafka-traffic-brokers", supply.brokers(),
            "--kafka-traffic-topic", supply.topic()
        }));
    }

    /**
     * A request the proxy captured comes back as a reconstructed transaction, not as records.
     *
     * <p>The request line is the assertion that matters: printing it means the bytes were reassembled from
     * however many read observations across however many records the proxy chose to emit, and parsed as one
     * HTTP message. A count of records would prove none of that.
     */
    @Test
    void dumpHttpReconstructsATransactionCapturedByTheRealProxy() throws Exception {
        var output = dump("dump-http");

        var requestLine = output.lines()
            .filter(line -> line.contains("REQ[") && line.contains("/reconstruct-me"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "the captured request was not reconstructed into a request line. Output:\n" + output
            ));
        var connectionIdentity = requestLine.substring(
            requestLine.indexOf(" nc:"),
            requestLine.indexOf(" REQ[")
        );
        Assertions.assertTrue(
            output.lines().anyMatch(line -> line.contains(connectionIdentity + " RSP[")),
            () -> "the proxy-captured connection's source response was not reconstructed. Output:\n" + output
        );
        // The probe and heartbeats create no connection state, so this mode must not render them: its subject
        // is transactions, and the topic demonstrably contains records that are not one.
        Assertions.assertTrue(
            output.lines().noneMatch(line -> line.contains("PROBE") || line.contains("HEARTBEAT")),
            () -> "dump-http rendered a record that produces no transaction. Output:\n" + output
        );
    }

    /**
     * A response whose completion nothing proved is marked {@code UNPROVEN}, and one a following request
     * proved is not.
     *
     * <p>{@code kafkaLLD §9.2}: only the next request's read observation on the same connection proves the
     * source finished a response. Truncation itself is undetectable — the capture protocol marks the end of a
     * request but not of a response, and the replayer deliberately does not parse response framing — so this is
     * the strongest honest claim the output can make, and it is what Plan A's exit criterion asks for now.
     *
     * <p>The close-ended connection is produced directly rather than through the proxy: a proxied request
     * completes, and there is no way to ask the proxy for a connection that closes mid-response. The bytes are
     * a real {@code CaptureRecord} on the real topic read through the real path.
     */
    @Test
    void aResponseWithNothingProvingItFinishedIsMarkedUnproven() throws Exception {
        var output = dump("dump-http");

        var unproven = output.lines()
            .filter(line -> line.contains("RSP[") && line.contains("UNPROVEN"))
            .toList();
        Assertions.assertFalse(
            unproven.isEmpty(),
            () -> "a response ended by a close must be marked unproven. Output:\n" + output
        );
        Assertions.assertTrue(
            unproven.stream().anyMatch(line -> line.contains("truncated-connection")),
            () -> "the close-ended connection's response is the one that must carry it. Output:\n" + output
        );
        Assertions.assertTrue(
            output.lines().anyMatch(line -> line.contains("REQ[") && line.contains("/truncated")),
            () -> "the request on that connection must still be reconstructed. Output:\n" + output
        );
        // Incomplete is now reserved for expiry and cancellation, neither of which a dump performs, so its
        // absence here is the assertion that the two outcomes have not been collapsed back together.
        Assertions.assertTrue(
            output.lines().noneMatch(line -> line.contains("RSP INCOMPLETE")),
            () -> "a dump never expires or cancels a generation, so nothing may be reported incomplete."
                + " Output:\n" + output
        );
    }

    /** {@code dump-both} is the two views interleaved, which is why they share a column layout. */
    @Test
    void dumpBothRendersRecordsAndTransactionsTogether() throws Exception {
        var output = dump("dump-both");

        Assertions.assertTrue(
            output.contains("PROBE"),
            () -> "dump-both must keep the per-record view, including the startup probe. Output:\n" + output
        );
        Assertions.assertTrue(
            output.lines().anyMatch(line -> line.contains("REQ[") && line.contains("/reconstruct-me")),
            () -> "dump-both must also render the reconstructed transaction. Output:\n" + output
        );
    }

    /**
     * One connection whose response is cut off by a close: read, end-of-message, a partial write, then
     * {@code CloseObservation}.
     *
     * <p>Every observation carries its connection-local sequence, contiguous from its own baseline, because
     * {@code proxyCaptureProtocol §4.1} has the proxy assign one and {@code kafkaLLD §9} validates it. A
     * fixture omitting them would be rejected as a protocol violation, which is the validation working.
     */
    private static CaptureRecord truncatedTransaction() {
        var stream = TrafficStream.newBuilder()
            .setNodeId("synthetic-writer")
            .setConnectionId("truncated-connection")
            .setNumber(0)
            .addSubStream(observation(1)
                .setRead(ReadObservation.newBuilder()
                    .setData(utf8("GET /truncated HTTP/1.1\r\nHost: source\r\n\r\n"))))
            .addSubStream(observation(2)
                .setEndOfMessageIndicator(EndOfMessageIndication.getDefaultInstance()))
            .addSubStream(observation(3)
                .setWrite(WriteObservation.newBuilder()
                    .setData(utf8("HTTP/1.1 200 OK\r\nContent-Length: 99\r\n\r\ncut-off-here"))))
            .addSubStream(observation(4).setClose(CloseObservation.getDefaultInstance()))
            .build();
        return CaptureRecord.newBuilder().setTrafficStream(stream).build();
    }

    private static TrafficObservation.Builder observation(long sequence) {
        return TrafficObservation.newBuilder()
            .setTs(Timestamp.newBuilder().setSeconds(1_700_000_000L + sequence).build())
            .setConnectionObservationSequence(sequence);
    }

    private static ByteString utf8(String value) {
        return ByteString.copyFromUtf8(value);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * The dumper writes to {@code System.out} because its output is the product — a person reads it. That makes
     * stdout the real contract, so this asserts on it rather than on an injected sink.
     */
    private static String captureStdout(ThrowingRunnable body) throws Exception {
        var captured = new ByteArrayOutputStream();
        var original = System.out;
        try (var replacement = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(replacement);
            body.run();
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
