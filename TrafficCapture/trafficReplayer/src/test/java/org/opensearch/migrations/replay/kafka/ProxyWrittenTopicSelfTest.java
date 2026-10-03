/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafka;

import java.util.ArrayList;

import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;

import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Proves the supply side of G1 before anything consumes it: the real capture proxy, unmodified, writes
 * records this module can decode as {@code CaptureRecord} envelopes.
 *
 * <p>This is the claim that cannot be made with a hand-written producer. A test that wrote its own
 * records would encode this module's belief about the wire format, and so would agree with itself no
 * matter how far that belief had drifted from the proxy. Defect {@code D1} — "the replayer cannot read
 * its own capture topic" — is exactly that drift going unnoticed.
 *
 * <p>Deliberately separate from any test of the dump output. This one answers "does the proxy produce
 * what we think"; a dumper test answers "do we render it correctly". Collapsing them would mean a
 * formatting change could mask a format change.
 *
 * <p>Requires Docker, hence {@code isolatedTest}.
 */
@Tag("isolatedTest")
class ProxyWrittenTopicSelfTest {

    @Test
    void realProxyWritesCaptureRecordEnvelopesContainingTrafficStreams() throws Exception {
        try (var supply = ProxyWrittenTopic.start("g1-supply-side")) {
            var status = supply.sendGet("/");
            Assertions.assertEquals(
                200,
                status,
                "the request did not reach the destination through the proxy, so nothing would be captured"
            );

            // Gated on a durable TrafficStream, not on "at least one record": the proxy writes a capability
            // probe at startup, so a count of one is satisfied before the GET above is captured at all.
            Assertions.assertFalse(
                supply.readTrafficStreamValues(1).isEmpty(),
                "the proxy accepted the request but wrote no TrafficStream to " + supply.topic()
            );
            var values = supply.readRecordValues(1);

            Assertions.assertFalse(
                values.isEmpty(),
                "the proxy accepted the request but wrote no record to " + supply.topic()
            );

            var payloadCases = new ArrayList<CaptureRecord.PayloadCase>();
            for (var value : values) {
                CaptureRecord envelope;
                try {
                    envelope = CaptureRecord.parseFrom(value);
                } catch (InvalidProtocolBufferException notAnEnvelope) {
                    throw new AssertionError(
                        "the proxy wrote "
                            + value.length
                            + " bytes that are not a CaptureRecord envelope; the replayer's assumption about"
                            + " the wire format has drifted from what the proxy emits",
                        notAnEnvelope
                    );
                }
                Assertions.assertNotEquals(
                    CaptureRecord.PayloadCase.PAYLOAD_NOT_SET,
                    envelope.getPayloadCase(),
                    "the proxy wrote an envelope with no payload set, which the design treats as a protocol"
                        + " violation rather than a case to tolerate"
                );
                payloadCases.add(envelope.getPayloadCase());
            }

            Assertions.assertTrue(
                payloadCases.contains(CaptureRecord.PayloadCase.TRAFFICSTREAM),
                "no captured request appeared on the topic; only got " + payloadCases
            );
        }
    }
}
