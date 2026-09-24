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
import java.util.List;

import org.opensearch.migrations.replay.TrafficReplayer;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * G1's exit evidence: this module reads and dumps a topic written by the current, unmodified proxy, and
 * handles every {@code CaptureRecord} envelope case explicitly.
 *
 * <p>Distinct from {@link ProxyWrittenTopicSelfTest}, which proves the proxy produces envelopes this
 * module can parse. This one proves the dumper renders them. Keeping the claims apart is what stops a
 * formatting change from masking a format change, and it is why the fixture deliberately does not decode.
 *
 * <p>Requires Docker, hence {@code isolatedTest}.
 */
@Tag("isolatedTest")
class KafkaTopicDumperEvidenceTest {

    @Test
    void dumpRawRendersEveryEnvelopeCaseFromARealProxyTopic() throws Exception {
        try (var supply = ProxyWrittenTopic.start("g1-dump-evidence")) {
            Assertions.assertEquals(200, supply.sendGet("/"));
            // Wait for durability before dumping: the dumper stops at the endOffsets snapshot it takes at
            // startup, so a record still in flight would simply not be in the range it reads.
            Assertions.assertFalse(
                supply.readTrafficStreamValues(1).isEmpty(),
                "no captured traffic reached the topic; the startup capability probe does not count, which is"
                    + " why this waits for a TrafficStream rather than for any record"
            );

            // Through TrafficReplayer.main, not by calling the dumper directly. Calling the dumper would
            // prove the dumper works while leaving the CLI dispatch unexercised, and an entry point with no
            // caller is exactly what AGENTS.md section 4 refuses to count as wired.
            // No --target-uri: dumping is a read-only inspection of the capture topic and must not require a
            // replay target to exist.
            var output = captureStdout(() -> TrafficReplayer.main(new String[] {
                "--mode", "dump-raw",
                "--kafka-traffic-brokers", supply.brokers(),
                "--kafka-traffic-topic", supply.topic()
            }));

            Assertions.assertFalse(output.isBlank(), "the dumper read the topic but printed nothing");

            // The probe record is written by the proxy's own startup capability check, so a real topic always
            // has one. It is the case most likely to be dropped by a non-exhaustive decoder, which makes it
            // the most useful thing to assert on.
            Assertions.assertTrue(
                output.contains("PROBE"),
                () -> "no CaptureCapabilityProbe was rendered; the proxy writes one at startup. Output:\n" + output
            );
            Assertions.assertTrue(
                output.lines().anyMatch(line -> line.contains("p:") && line.contains("o:")),
                () -> "records were rendered without partition/offset metadata. Output:\n" + output
            );
            // The captured request itself. TrafficStream lines carry the connection id rather than a literal
            // marker, so assert on the request line the destination actually received.
            Assertions.assertTrue(
                output.contains("GET"),
                () -> "the captured request was not rendered. Output:\n" + output
            );
        }
    }

    /**
     * A dump of a multi-partition topic renders records from every partition, and the partition and offset it
     * prints are the ones Kafka reported.
     *
     * <p>This is the case a single-partition topic cannot express, and it is why the truncation defect survived:
     * {@code runRawFromKafka} returned from the whole dump on the first record past a bound, while the bound is
     * per-partition and one poll interleaves partitions. With one partition that return is indistinguishable
     * from finishing.
     *
     * <p>The rendered metadata is compared against what a consumer independently reports for the same records,
     * rather than merely checked for being present, so a dumper printing a plausible but wrong partition fails.
     */
    @Test
    void dumpRawRendersEveryPartitionAndTheMetadataKafkaReported() throws Exception {
        try (var supply = ProxyWrittenTopic.start("g1-dump-multipartition", 3)) {
            // Several requests, so the proxy's partitioner spreads connections across partitions.
            for (var i = 0; i < 12; i++) {
                Assertions.assertEquals(200, supply.sendGet("/req-" + i));
            }
            var captured = supply.readTrafficStreamValues(1);
            Assertions.assertFalse(captured.isEmpty(), "nothing was captured to dump");

            // Every partition is populated deliberately, by replaying a proxy-written envelope onto each one.
            // Relying on the proxy's partitioner would mean assuming the spread and skipping when it did not
            // happen — and a skip that fires is indistinguishable from coverage that never existed, which is
            // exactly how the truncation defect survived a green suite. The bytes are still the real proxy's.
            for (var partition = 0; partition < 3; partition++) {
                supply.produceDirectly(partition, captured.get(0));
            }

            var consumed = supply.readRecordMetadata();
            var populatedPartitions = consumed.stream().map(ProxyWrittenTopic.RecordLocation::partition)
                .distinct().sorted().toList();
            Assertions.assertEquals(
                List.of(0, 1, 2),
                populatedPartitions,
                "every partition must hold a record, or this proves nothing about multi-partition dumping"
            );

            var output = captureStdout(() -> TrafficReplayer.main(new String[] {
                "--mode", "dump-raw",
                "--kafka-traffic-brokers", supply.brokers(),
                "--kafka-traffic-topic", supply.topic()
            }));

            // Whitespace is removed from both sides rather than the expected string being built to match the
            // dumper's format. The offset is rendered right-padded to a fixed width for readability, so
            // coupling to that padding would turn a presentation change into a false "record dropped" — and
            // collapsing runs of spaces is not enough, since `o:%6d` of zero leaves a space after the colon.
            var withoutSpacing = output.replaceAll("\\s", "");
            for (var location : consumed) {
                var rendered = "p:" + location.partition() + " o:" + location.offset();
                var expected = rendered.replaceAll("\\s", "");
                Assertions.assertTrue(
                    withoutSpacing.contains(expected),
                    () -> "the dump omitted " + rendered + ", so a partition's records were dropped or their"
                        + " metadata was rendered wrong. Partitions with records: " + populatedPartitions
                        + "\nOutput:\n" + output
                );
            }
        }
    }

    /**
     * All three payload cases the proxy produces reach the topic and are rendered.
     *
     * <p>The heartbeat is why this is a separate test. The proxy writes one on a timer rather than in response
     * to traffic, so the other tests here never see one and "every envelope case is decoded" was an assumption
     * about the case most likely to be missing — a decoder that dropped `WRITERPARTITIONHEARTBEAT` would have
     * passed everything else. The fixture runs the proxy with a one-second heartbeat interval so the wait is
     * short rather than the production interval.
     */
    @Test
    void dumpRawRendersTrafficProbeAndHeartbeatFromARealProxy() throws Exception {
        try (var supply = ProxyWrittenTopic.start("g1-dump-all-payloads")) {
            Assertions.assertEquals(200, supply.sendGet("/"));

            var wanted = java.util.Set.of(
                CaptureRecord.PayloadCase.TRAFFICSTREAM,
                CaptureRecord.PayloadCase.CAPTURECAPABILITYPROBE,
                CaptureRecord.PayloadCase.WRITERPARTITIONHEARTBEAT
            );
            var seen = supply.awaitPayloadCases(wanted, Duration.ofSeconds(60));
            Assertions.assertTrue(
                seen.containsAll(wanted),
                () -> "the proxy did not write every payload case within the timeout; saw " + seen
            );

            var output = captureStdout(() -> TrafficReplayer.main(new String[] {
                "--mode", "dump-raw",
                "--kafka-traffic-brokers", supply.brokers(),
                "--kafka-traffic-topic", supply.topic()
            }));

            Assertions.assertTrue(output.contains("PROBE"), () -> "no probe rendered. Output:\n" + output);
            Assertions.assertTrue(output.contains("GET"), () -> "no captured request rendered. Output:\n" + output);
            Assertions.assertTrue(
                output.contains("HEARTBEAT"),
                () -> "no WriterPartitionHeartbeat rendered, though one is on the topic. Output:\n" + output
            );
        }
    }

    /**
     * A record that is not a {@code CaptureRecord} envelope ends the dump with a message naming where it is,
     * rather than being skipped or rendered as something else.
     *
     * <p>A correct proxy cannot produce this, so it is written directly. The behaviour is a stated protocol
     * requirement: {@code kafkaLLD §16} makes an undecodable record fatal, and silently tolerating one would let
     * a producer-side format change pass as an empty or partial dump.
     */
    @Test
    void anUndecodableRecordEndsTheDumpAndNamesItsLocation() throws Exception {
        try (var supply = ProxyWrittenTopic.start("g1-dump-malformed")) {
            Assertions.assertEquals(200, supply.sendGet("/"));
            Assertions.assertFalse(supply.readTrafficStreamValues(1).isEmpty(), "nothing was captured");
            supply.produceDirectly(0, "this is not protobuf".getBytes(StandardCharsets.UTF_8));

            var thrown = Assertions.assertThrows(Exception.class, () -> captureStdout(() ->
                TrafficReplayer.main(new String[] {
                    "--mode", "dump-raw",
                    "--kafka-traffic-brokers", supply.brokers(),
                    "--kafka-traffic-topic", supply.topic()
                })
            ));

            var message = String.valueOf(thrown.getMessage());
            Assertions.assertTrue(
                message.contains(supply.topic()) && message.contains("CaptureRecord"),
                () -> "the failure must name the record's location and what was wrong with it, so an operator"
                    + " can find it; got: " + message
            );
        }
    }

    /**
     * A syntactically valid envelope with no payload is detected by replay intake. The owner-thread path must
     * propagate that asynchronous violation back to the dump command rather than printing it and continuing.
     */
    @Test
    void aPayloadlessEnvelopeEndsTheHttpDumpAndNamesItsRecord() throws Exception {
        try (var supply = ProxyWrittenTopic.start("g3-http-dump-payloadless")) {
            Assertions.assertEquals(200, supply.sendGet("/"));
            Assertions.assertFalse(supply.readTrafficStreamValues(1).isEmpty(), "nothing was captured");
            supply.produceDirectly(0, CaptureRecord.getDefaultInstance().toByteArray());

            var thrown = Assertions.assertThrows(Exception.class, () -> captureStdout(() ->
                TrafficReplayer.main(new String[] {
                    "--mode", "dump-http",
                    "--kafka-traffic-brokers", supply.brokers(),
                    "--kafka-traffic-topic", supply.topic()
                })
            ));

            var root = thrown;
            while (root.getCause() instanceof Exception cause) {
                root = cause;
            }
            var message = String.valueOf(root.getMessage());
            Assertions.assertTrue(
                message.contains("Capture protocol violation") && message.contains(supply.topic()),
                () -> "the HTTP dump must fail and identify the invalid record; got: " + message
            );
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * The dumper writes to {@code System.out} because its output is the product — a person reads it. That
     * makes stdout the real contract, so the test asserts on it rather than on an injected sink that would
     * prove something else.
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
