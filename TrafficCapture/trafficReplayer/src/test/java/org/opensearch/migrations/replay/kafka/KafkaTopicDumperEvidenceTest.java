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
            Assertions.assertFalse(supply.readRecordValues(1).isEmpty(), "nothing was captured to dump");

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
