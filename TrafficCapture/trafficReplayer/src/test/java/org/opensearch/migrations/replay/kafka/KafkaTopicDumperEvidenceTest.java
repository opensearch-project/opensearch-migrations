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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.TrafficReplayer;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionBatchRequestId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInput;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInputQueue;
import org.opensearch.migrations.replay.kafkasource.WakeupController;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import org.apache.kafka.common.TopicPartition;
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
    void protocolViolationIsPrimaryWhenOwnerFailureIsAlsoPending() {
        var generation = new PartitionGenerationId(new TopicPartition("traffic", 0), 0);
        var demand = new KafkaTopicDumper.DumpBatchDemand(Set.of(generation));
        var ownerFailure = new AtomicReference<Error>();

        try (var telemetry = new InMemoryInstrumentationBundle(false, false)) {
            var sourceInputs = new KafkaSourceInputQueue(
                new WakeupController(
                    () -> {},
                    new RootReplayerContext(telemetry.openTelemetrySdk)
                )
            );
            Assertions.assertEquals(
                new PartitionBatchRequestId(generation, 0),
                Assertions.assertTimeoutPreemptively(
                    Duration.ofSeconds(5),
                    () -> demand.awaitNextRequestId(generation, sourceInputs, ownerFailure)
                )
            );

            var internalFailure = new Error("simultaneous intake failure");
            ownerFailure.set(internalFailure);
            var violatingRecord = new KafkaRecordId(generation, 42);
            sourceInputs.submit(new KafkaSourceInput.CaptureProtocolViolationDetected(
                violatingRecord,
                "payload is absent"
            ));

            var thrown = Assertions.assertThrows(
                CaptureRecordProtocolViolationException.class,
                () -> demand.awaitNextRequestId(generation, sourceInputs, ownerFailure)
            );
            Assertions.assertTrue(thrown.getMessage().contains(violatingRecord.toString()));
            Assertions.assertArrayEquals(new Throwable[] { internalFailure }, thrown.getSuppressed());
        }
    }

    @Test
    void successorBatchWaitsForTheExactPerPartitionDemandIdentity() throws Exception {
        var first = new PartitionGenerationId(new TopicPartition("traffic", 0), 0);
        var second = new PartitionGenerationId(new TopicPartition("traffic", 1), 0);
        var demand = new KafkaTopicDumper.DumpBatchDemand(Set.of(first, second));
        var ownerFailure = new AtomicReference<Error>();

        try (var telemetry = new InMemoryInstrumentationBundle(false, false)) {
            var executor = Executors.newSingleThreadExecutor();
            var sourceInputs = new KafkaSourceInputQueue(
                new WakeupController(
                    () -> {},
                    new RootReplayerContext(telemetry.openTelemetrySdk)
                )
            );

            Assertions.assertEquals(
                new PartitionBatchRequestId(first, 0),
                Assertions.assertTimeoutPreemptively(
                    Duration.ofSeconds(5),
                    () -> demand.awaitNextRequestId(first, sourceInputs, ownerFailure)
                )
            );
            Assertions.assertEquals(
                new PartitionBatchRequestId(second, 0),
                Assertions.assertTimeoutPreemptively(
                    Duration.ofSeconds(5),
                    () -> demand.awaitNextRequestId(second, sourceInputs, ownerFailure)
                )
            );

            try {
                var waiterStarted = new CountDownLatch(1);
                var firstSuccessor = executor.submit(() -> {
                    waiterStarted.countDown();
                    return demand.awaitNextRequestId(first, sourceInputs, ownerFailure);
                });
                Assertions.assertTrue(waiterStarted.await(5, TimeUnit.SECONDS));

                var secondRequest = new PartitionBatchRequestId(second, 1);
                sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(secondRequest));
                Assertions.assertThrows(
                    TimeoutException.class,
                    () -> firstSuccessor.get(100, TimeUnit.MILLISECONDS),
                    "another partition's demand must not release this partition"
                );

                var firstRequest = new PartitionBatchRequestId(first, 1);
                sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(firstRequest));
                Assertions.assertEquals(firstRequest, firstSuccessor.get(5, TimeUnit.SECONDS));
                Assertions.assertEquals(
                    secondRequest,
                    Assertions.assertTimeoutPreemptively(
                        Duration.ofSeconds(5),
                        () -> demand.awaitNextRequestId(second, sourceInputs, ownerFailure)
                    ),
                    "the other partition's exact request must remain pending"
                );
            } finally {
                executor.shutdownNow();
                Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

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
     * HTTP dumping must consume the explicit demand entitlement before delivering a successor batch.
     *
     * <p>More than one Kafka poll is forced on each partition with capability probes, which create no
     * connection state. The former dumper synthesized successor batch IDs and raced ahead of replay intake;
     * the second batch then failed because no matching explicit request had been observed.
     */
    @Test
    void dumpBothWaitsForExplicitDemandAcrossPartitionsAndPolls() throws Exception {
        try (var supply = ProxyWrittenTopic.start("g10-dump-demand", 2)) {
            Assertions.assertEquals(200, supply.sendGet("/demand-one"));
            Assertions.assertEquals(200, supply.sendGet("/demand-two"));
            Assertions.assertFalse(supply.readTrafficStreamValues(2).isEmpty());
            var probe = supply.readAllRecordValues().stream()
                .filter(value -> {
                    try {
                        return CaptureRecord.parseFrom(value).hasCaptureCapabilityProbe();
                    } catch (Exception notAnEnvelope) {
                        return false;
                    }
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError("the proxy startup probe was not durable"));
            // Kafka's default max.poll.records is 500. More than two polls ensures the request-bearing
            // bootstrap can close ordinary request supply before a later probe-only successor batch arrives.
            // Without the dump-mode ConnectionRequestFinished lifecycle input, intake issues no successor
            // demand and this test waits rather than completing.
            var probes = java.util.Collections.nCopies(1_200, probe);
            for (var partition = 0; partition < 2; partition++) {
                var demandClosingRecords = new java.util.ArrayList<byte[]>();
                for (var request = 0; request < 2; request++) {
                    demandClosingRecords.add(completeTransaction(partition, request));
                }
                demandClosingRecords.addAll(probes);
                supply.produceDirectly(partition, demandClosingRecords);
            }
            var expected = supply.readRecordMetadata();

            var output = captureStdout(() -> TrafficReplayer.main(new String[] {
                "--mode", "dump-both",
                "--kafka-traffic-brokers", supply.brokers(),
                "--kafka-traffic-topic", supply.topic()
            }));

            var withoutSpacing = output.replaceAll("\\s", "");
            for (var location : expected) {
                var rendered = ("p:" + location.partition() + " o:" + location.offset())
                    .replaceAll("\\s", "");
                Assertions.assertTrue(
                    withoutSpacing.contains(rendered),
                    () -> "dump-both omitted " + location + " after crossing a poll boundary"
                );
            }
        }
    }

    private static byte[] completeTransaction(int partition, int request) {
        var path = "/partition-" + partition + "/request-" + request;
        var stream = TrafficStream.newBuilder()
            .setNodeId("synthetic-dump-demand-" + partition)
            .setConnectionId("request-" + request)
            .setNumber(0)
            .addSubStream(observation(1).setRead(ReadObservation.newBuilder().setData(
                ByteString.copyFromUtf8("GET " + path + " HTTP/1.1\r\nHost: source\r\n\r\n")
            )))
            .addSubStream(observation(2).setEndOfMessageIndicator(
                EndOfMessageIndication.getDefaultInstance()
            ))
            .addSubStream(observation(3).setWrite(WriteObservation.newBuilder().setData(
                ByteString.copyFromUtf8("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
            )))
            .addSubStream(observation(4).setClose(CloseObservation.getDefaultInstance()))
            .build();
        return CaptureRecord.newBuilder().setTrafficStream(stream).build().toByteArray();
    }

    private static TrafficObservation.Builder observation(long sequence) {
        return TrafficObservation.newBuilder()
            .setTs(Timestamp.newBuilder().setSeconds(1_700_000_000L + sequence))
            .setConnectionObservationSequence(sequence);
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
