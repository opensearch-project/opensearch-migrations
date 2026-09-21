/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.lifecycle.RecordWorkTracker;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.KafkaRecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.RecordAssociationId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceRequestAssemblyId;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.traffic.generator.RecordScript;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RecordAssociationAccumulatorTest {
    private static final String TOPIC = "traffic";
    private static final String WRITER = "writer";
    private static final String CONNECTION = "connection";
    private static final Timestamp OBSERVATION_TIME = Timestamp.newBuilder().setSeconds(1).build();

    @Test
    void mixedKeepAliveRecordMatchesLiteralRecordScriptAssociations() {
        var stream = stream(
            0,
            read("GET /0 HTTP/1.1\r\n\r\n"),
            endOfMessage(),
            write("HTTP/1.1 200 OK\r\n\r\n"),
            read("GET /1 HTTP/1.1\r\n\r\n")
        );
        var script = new RecordScript(TOPIC).addTraffic(
            0,
            0,
            Instant.ofEpochMilli(1_000),
            WRITER,
            stream,
            "request-0",
            "assembly-1"
        );
        var fixture = new AccumulatorFixture();

        fixture.accept(script.next().envelope().getTrafficStream());

        var recordId = new KafkaRecordId(TOPIC, 0, 0, 0);
        script.assertAssociations(
            new RecordScript.RecordId(new org.apache.kafka.common.TopicPartition(TOPIC, 0), 0),
            associationNames(fixture.tracker.associations(recordId))
        );
        Assertions.assertFalse(fixture.tracker.completionEmitted(recordId));

        fixture.tracker.associationFinished(fixture.request(0));
        Assertions.assertFalse(
            fixture.tracker.completionEmitted(recordId),
            "request N+1 assembly must continue to hold the mixed record"
        );
        fixture.tracker.associationFinished(fixture.assembly(1));
        Assertions.assertEquals(List.of(recordId), fixture.completions);
        script.assertExhausted();
    }

    @Test
    void sourceResponseRecordRemainsAssociatedUntilRequestProcessingFinishes() {
        var first = stream(0, read("GET / HTTP/1.1\r\n\r\n"), endOfMessage());
        var response = stream(1, write("HTTP/1.1 200 OK\r\n\r\n"));
        var script = new RecordScript(TOPIC)
            .addTraffic(0, 0, Instant.ofEpochMilli(1_000), WRITER, first, "request-0")
            .addTraffic(0, 1, Instant.ofEpochMilli(2_000), WRITER, response, "request-0");
        var fixture = new AccumulatorFixture();

        for (var scriptedRecord : script.records()) {
            fixture.accept(scriptedRecord.envelope().getTrafficStream());
            script.assertAssociations(
                scriptedRecord.id(),
                associationNames(fixture.tracker.associations(
                    new KafkaRecordId(TOPIC, 0, scriptedRecord.id().offset(), 0)
                ))
            );
        }

        Assertions.assertTrue(fixture.completions.isEmpty());
        fixture.tracker.associationFinished(fixture.request(0));
        Assertions.assertEquals(
            List.of(
                new KafkaRecordId(TOPIC, 0, 0, 0),
                new KafkaRecordId(TOPIC, 0, 1, 0)
            ),
            fixture.completions
        );
    }

    private static Collection<String> associationNames(Set<RecordAssociationId> associations) {
        return associations.stream().map(association -> {
            if (association instanceof ReplayRequestId request) {
                return "request-" + request.requestIndex();
            }
            if (association instanceof SourceRequestAssemblyId assembly) {
                return "assembly-" + assembly.requestIndex();
            }
            return "terminal-" + association;
        }).toList();
    }

    private static TrafficStream stream(int number, TrafficObservation... observations) {
        return TrafficStream.newBuilder()
            .setNodeId(WRITER)
            .setConnectionId(CONNECTION)
            .setNumber(number)
            .addAllSubStream(List.of(observations))
            .build();
    }

    private static TrafficObservation read(String data) {
        return TrafficObservation.newBuilder()
            .setTs(OBSERVATION_TIME)
            .setRead(ReadObservation.newBuilder().setData(ByteString.copyFromUtf8(data)))
            .build();
    }

    private static TrafficObservation write(String data) {
        return TrafficObservation.newBuilder()
            .setTs(OBSERVATION_TIME)
            .setWrite(WriteObservation.newBuilder().setData(ByteString.copyFromUtf8(data)))
            .build();
    }

    private static TrafficObservation endOfMessage() {
        return TrafficObservation.newBuilder()
            .setTs(OBSERVATION_TIME)
            .setEndOfMessageIndicator(EndOfMessageIndication.getDefaultInstance())
            .build();
    }

    private static final class AccumulatorFixture {
        private final TestContext rootContext = TestContext.noOtelTracking();
        private final List<KafkaRecordId> completions = new ArrayList<>();
        private final RecordWorkTracker tracker = new RecordWorkTracker(
            ignored -> Assertions.fail("no asynchronous tracker input expected"),
            () -> true,
            completions::add
        );
        private final CapturedTrafficToHttpTransactionAccumulator accumulator =
            new CapturedTrafficToHttpTransactionAccumulator(
                Duration.ofSeconds(30),
                null,
                callbacks(),
                false,
                tracker,
                key -> new KafkaRecordId(TOPIC, 0, key.getTrafficStreamIndex(), 0)
            );

        private void accept(TrafficStream trafficStream) {
            var key = PojoTrafficStreamKeyAndContext.build(
                trafficStream,
                rootContext::createTrafficStreamContextForTest
            );
            accumulator.accept(new PojoTrafficStreamAndKey(trafficStream, key));
        }

        private ReplayRequestId request(int requestIndex) {
            return tracker.associations(new KafkaRecordId(TOPIC, 0, 0, 0))
                .stream()
                .filter(ReplayRequestId.class::isInstance)
                .map(ReplayRequestId.class::cast)
                .filter(request -> request.requestIndex() == requestIndex)
                .findFirst()
                .orElseThrow();
        }

        private SourceRequestAssemblyId assembly(int requestIndex) {
            return tracker.associations(new KafkaRecordId(TOPIC, 0, 0, 0))
                .stream()
                .filter(SourceRequestAssemblyId.class::isInstance)
                .map(SourceRequestAssemblyId.class::cast)
                .filter(assembly -> assembly.requestIndex() == requestIndex)
                .findFirst()
                .orElseThrow();
        }

        private static AccumulationCallbacks callbacks() {
            return new AccumulationCallbacks() {
                @Override
                public Consumer<RequestResponsePacketPair> onRequestReceived(
                    @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                    @NonNull HttpMessageAndTimestamp request,
                    boolean isResumedConnection
                ) {
                    return ignored -> {};
                }

                @Override
                public void onTrafficStreamsExpired(
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {}

                @Override
                public void onConnectionClose(
                    int channelInteractionNumber,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    int channelSessionNumber,
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull Instant when,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {}

                @Override
                public void onTrafficStreamIgnored(
                    @NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx
                ) {}
            };
        }
    }
}
