package org.opensearch.migrations.replay.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.AccumulationCallbacks;
import org.opensearch.migrations.replay.CapturedTrafficToHttpTransactionAccumulator;
import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.RequestResponsePacketPair;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.testutils.SharedDockerImageNames;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/**
 * Real-Kafka regression test for the "Stale accumulation found" production failure.
 *
 * <p>Drives an actual {@link ConfluentKafkaContainer} (matching {@link KafkaKeepAliveTests} and
 * {@link KafkaCommitsWorkBetweenLongPollsTest}) with a {@code max.poll.interval.ms} short enough
 * that we can deterministically force the broker to fence the consumer between polls. When the
 * consumer rejoins, the broker re-assigns the same partition back to it through the cooperative
 * Revoked → Assigned path.
 *
 * <p>The bug under test: pre-fix, that round-trip silently bumped the consumer generation
 * without enqueuing a {@link TrafficSourceReaderInterruptedClose} for the in-flight connection.
 * The next poll re-delivered the record with the new generation, the accumulator's stored
 * gen-1 accumulation tripped the stale-accumulation defensive backstop, and the channel
 * session was never properly cancelled.
 *
 * <p>Post-fix invariants verified end-to-end against a real broker:
 * <ol>
 *   <li>A {@code TrafficSourceReaderInterruptedClose} <em>is</em> delivered for the in-flight
 *       connection after the broker rebalances the partition back to the same consumer.</li>
 *   <li>The synthetic close arrives at the accumulator <em>before</em> any re-delivered
 *       new-generation record for that connection.</li>
 *   <li>The in-flight accumulation is closed with {@code TRAFFIC_SOURCE_READER_INTERRUPTED}
 *       so {@code replayEngine.cancelConnection} runs.</li>
 *   <li>The accumulator's defensive stale-accumulation backstop does <em>not</em> fire — proper
 *       handling now happens entirely at the source layer.</li>
 * </ol>
 *
 * <p>This complements the in-memory {@link StaleAccumulationCancelOnRejoinTest} (which uses
 * {@code MockConsumer}) by validating the same behavior against the real Kafka client's
 * rebalance machinery.
 */
@Slf4j
@Testcontainers(disabledWithoutDocker = true)
@Tag("isolatedTest")
public class StaleAccumulationCancelOnRejoinKafkaTest extends InstrumentationTest {

    private static final String TOPIC = "stale-accum-rejoin-topic";
    private static final String GROUP = "stale-accum-rejoin-group";
    private static final String NODE_ID = "node1";
    private static final String CONN_ID = "conn-mid-flight";

    /**
     * Short enough that we can deliberately exceed it to force a fence + rejoin, but above the
     * broker's {@code group.min.session.timeout.ms} (default 6000ms in Confluent) since we set
     * {@code session.timeout.ms} to a value just under {@code MAX_POLL_INTERVAL_MS} below.
     */
    private static final long MAX_POLL_INTERVAL_MS = 8_000;
    private static final long SESSION_TIMEOUT_MS = 7_000;

    @Container
    private final ConfluentKafkaContainer embeddedKafkaBroker =
        new ConfluentKafkaContainer(SharedDockerImageNames.KAFKA);

    @Test
    void revokeAndReassign_realKafka_synthClosesBeforeNewGenRecord() throws Exception {
        var producer = KafkaTestUtils.buildKafkaProducer(embeddedKafkaBroker.getBootstrapServers());

        // Capture every callback emitted by the accumulator so we can assert exact ordering
        // and final close status.
        var connectionCloseStatuses =
            new ArrayList<RequestResponsePacketPair.ReconstructionStatus>();
        var connectionCloseConnIds = new ArrayList<String>();
        var requestsReceived = new AtomicInteger();
        var responsesCompleted = new AtomicInteger();

        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofSeconds(60), null, new AccumulationCallbacks() {
                @Override
                public Consumer<RequestResponsePacketPair> onRequestReceived(
                    @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                    @NonNull HttpMessageAndTimestamp request,
                    boolean isResumedConnection
                ) {
                    requestsReceived.incrementAndGet();
                    return pair -> responsesCompleted.incrementAndGet();
                }

                @Override
                public void onTrafficStreamsExpired(
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {
                    for (var tsk : trafficStreamKeysBeingHeld) tsk.getTrafficStreamsContext().close();
                }

                @Override
                public void onConnectionClose(
                    int channelInteractionNum,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    int sessionNumber,
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull Instant when,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {
                    connectionCloseStatuses.add(status);
                    connectionCloseConnIds.add(ctx.getConnectionId());
                    for (var tsk : trafficStreamKeysBeingHeld) tsk.getTrafficStreamsContext().close();
                }

                @Override
                public void onTrafficStreamIgnored(
                    @NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx
                ) {}
            }
        );

        var kafkaConsumerProps = buildShortPollIntervalConsumerProps();
        var kafkaConsumer = new KafkaConsumer<String, byte[]>(kafkaConsumerProps);

        try (var source = new KafkaTrafficCaptureSource(
                rootContext, kafkaConsumer, TOPIC,
                Duration.ofMillis(MAX_POLL_INTERVAL_MS / 4))
        ) {
            // ---- 1) Produce the first record (a complete request, ACCUMULATING_WRITES state) ----
            produceReadEomRecord(producer, 0L);

            // ---- 2) Read it. After this, the accumulator has a gen=N1 accumulation in
            //         ACCUMULATING_WRITES, and the broker tracks the consumer at generation N1.
            var firstObservedGen = drainUntilFoundConnId(source, accumulator, CONN_ID, 30);
            Assertions.assertEquals(1, requestsReceived.get(),
                "READ+EOM record should have produced exactly one request");
            Assertions.assertEquals(0, connectionCloseStatuses.size(),
                "no connection close should fire while the connection is still mid-flight");

            // ---- 3) Sleep past max.poll.interval.ms WITHOUT polling Kafka.
            //         The broker fences this consumer. On the NEXT poll(), the kafka client
            //         detects the fence and runs a cooperative rebalance: onPartitionsRevoked
            //         followed by onPartitionsAssigned for the same partition (single-consumer
            //         group → it always comes back). This is the production "Path 2".
            Thread.sleep(MAX_POLL_INTERVAL_MS * 2);

            // ---- 4) Drive readNextTrafficStreamChunk and observe the order of items the
            //         source delivers. The synthetic close MUST appear before any re-delivered
            //         record. (The first record at offset 0 was never committed, so Kafka resets
            //         the fetch position to 0 and re-delivers it.)
            var observed = drainUntilSyntheticAndConnIdSeen(source, accumulator, CONN_ID, 30);

            int firstSyntheticIdx = -1;
            int firstReDeliveredIdx = -1;
            for (int i = 0; i < observed.size(); i++) {
                var item = observed.get(i);
                if (firstSyntheticIdx < 0
                    && item instanceof TrafficSourceReaderInterruptedClose
                    && CONN_ID.equals(item.getKey().getConnectionId())) {
                    firstSyntheticIdx = i;
                }
                if (firstReDeliveredIdx < 0
                    && !(item instanceof TrafficSourceReaderInterruptedClose)
                    && CONN_ID.equals(item.getKey().getConnectionId())) {
                    firstReDeliveredIdx = i;
                }
            }
            Assertions.assertTrue(firstSyntheticIdx >= 0,
                "source layer must inject a TrafficSourceReaderInterruptedClose for the connection "
                    + "after the broker round-trips the partition through Revoked → Assigned. "
                    + "Observed delivery order = " + describeOrder(observed));
            Assertions.assertTrue(firstReDeliveredIdx >= 0,
                "Kafka must re-deliver the uncommitted record after the rebalance reset the fetch "
                    + "position. Observed = " + describeOrder(observed));
            Assertions.assertTrue(firstSyntheticIdx < firstReDeliveredIdx,
                "synthetic close MUST be processed BEFORE the re-delivered record so the in-flight "
                    + "accumulation is torn down cleanly. syntheticIdx=" + firstSyntheticIdx
                    + " reDeliveredIdx=" + firstReDeliveredIdx
                    + " order=" + describeOrder(observed));

            // ---- 5) End-to-end close semantics ----
            // There may be more than one onConnectionClose if the re-delivered record finished
            // and closed normally afterwards, but the FIRST close for CONN_ID must be the
            // interrupted-close (i.e., the source layer handled the rebalance at the source,
            // not the accumulator's defensive backstop).
            int firstCloseForConn = -1;
            for (int i = 0; i < connectionCloseConnIds.size(); i++) {
                if (CONN_ID.equals(connectionCloseConnIds.get(i))) {
                    firstCloseForConn = i;
                    break;
                }
            }
            Assertions.assertTrue(firstCloseForConn >= 0,
                "expected at least one onConnectionClose for connection " + CONN_ID
                    + "; got conn ids=" + connectionCloseConnIds);
            Assertions.assertEquals(
                RequestResponsePacketPair.ReconstructionStatus.TRAFFIC_SOURCE_READER_INTERRUPTED,
                connectionCloseStatuses.get(firstCloseForConn),
                "first close for the rebalanced connection must be TRAFFIC_SOURCE_READER_INTERRUPTED "
                    + "so replayEngine.cancelConnection runs and the channel session is marked "
                    + "cancelled. Statuses=" + connectionCloseStatuses);

            // The in-flight request's response future was completed by the synthetic close
            // (drains the OnlineRadixSorter).
            Assertions.assertTrue(responsesCompleted.get() >= 1,
                "fireAccumulationsCallbacksAndClose must complete the in-flight request's "
                    + "finishedAccumulatingResponseFuture so the sorter can drain. completed="
                    + responsesCompleted.get());
        } finally {
            producer.close();
        }
    }

    /**
     * Drains traffic streams from the source and feeds them into the accumulator. Returns when
     * a record matching {@code targetConnId} has been observed at least once. Bounds the work by
     * {@code maxAttempts} polls so the test cannot hang on a stuck Kafka.
     *
     * @return the {@link ITrafficStreamWithKey} for the first matching record (caller can read
     *         {@code getKey().getSourceGeneration()} from it).
     */
    private ITrafficStreamWithKey drainUntilFoundConnId(
        KafkaTrafficCaptureSource source,
        CapturedTrafficToHttpTransactionAccumulator accumulator,
        String targetConnId,
        int maxAttempts
    ) throws Exception {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            var batch = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();
            for (var ts : batch) {
                accumulator.accept(ts);
                if (!(ts instanceof TrafficSourceReaderInterruptedClose)
                    && targetConnId.equals(ts.getKey().getConnectionId())) {
                    return ts;
                }
            }
        }
        throw new AssertionError("drainUntilFoundConnId: never saw conn=" + targetConnId
            + " after " + maxAttempts + " polls");
    }

    /**
     * Drains traffic streams and returns the cumulative ordered list once we've seen BOTH a
     * synthetic close for {@code targetConnId} AND a real (non-synthetic) record for it. Bounded
     * by {@code maxAttempts}.
     */
    private List<ITrafficStreamWithKey> drainUntilSyntheticAndConnIdSeen(
        KafkaTrafficCaptureSource source,
        CapturedTrafficToHttpTransactionAccumulator accumulator,
        String targetConnId,
        int maxAttempts
    ) throws Exception {
        var observed = new ArrayList<ITrafficStreamWithKey>();
        boolean sawSynthetic = false;
        boolean sawReal = false;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            var batch = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();
            for (var ts : batch) {
                accumulator.accept(ts);
                observed.add(ts);
                if (targetConnId.equals(ts.getKey().getConnectionId())) {
                    if (ts instanceof TrafficSourceReaderInterruptedClose) sawSynthetic = true;
                    else sawReal = true;
                }
            }
            if (sawSynthetic && sawReal) return observed;
        }
        throw new AssertionError("drainUntilSyntheticAndConnIdSeen: did not see both synthetic close "
            + "and re-delivered record for " + targetConnId
            + " after " + maxAttempts + " polls. Observed=" + describeOrder(observed));
    }

    private static String describeOrder(List<ITrafficStreamWithKey> items) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            if (i > 0) sb.append(", ");
            sb.append(item instanceof TrafficSourceReaderInterruptedClose ? "SYNTH" : "REAL")
              .append("(conn=").append(item.getKey().getConnectionId())
              .append(", gen=").append(item.getKey().getSourceGeneration())
              .append(")");
        }
        return sb.append("]").toString();
    }

    private Properties buildShortPollIntervalConsumerProps() throws java.io.IOException {
        var props = KafkaTrafficCaptureSource.buildKafkaProperties(
            embeddedKafkaBroker.getBootstrapServers(),
            GROUP,
            "none",
            null, null, null
        );
        // Force a fence whenever we sleep past max.poll.interval.ms between polls. The fence is
        // triggered by max.poll.interval.ms (the broker fences us when we go too long without
        // calling poll), not by session.timeout.ms (heartbeats are sent on a background thread).
        props.setProperty(KafkaTrafficCaptureSource.MAX_POLL_INTERVAL_KEY,
            String.valueOf(MAX_POLL_INTERVAL_MS));
        // session.timeout.ms must satisfy broker's group.min.session.timeout.ms (default 6s on
        // Confluent). Keep it just under max.poll.interval.ms.
        props.setProperty("session.timeout.ms", String.valueOf(SESSION_TIMEOUT_MS));
        props.setProperty("heartbeat.interval.ms", String.valueOf(SESSION_TIMEOUT_MS / 4));
        // Process records one at a time so the in-flight one is the only mid-flight accumulation
        // when the rebalance fires.
        props.setProperty("max.poll.records", "1");
        return props;
    }

    private void produceReadEomRecord(Producer<String, byte[]> producer, long offset) throws Exception {
        var ts = Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()).build();
        var stream = TrafficStream.newBuilder()
            .setNodeId(NODE_ID).setConnectionId(CONN_ID).setNumberOfThisLastChunk(0)
            .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom("GET / HTTP/1.1\r\nHost: x\r\n\r\n",
                        StandardCharsets.UTF_8))
                    .build())
                .build())
            .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setEndOfMessageIndicator(
                    EndOfMessageIndication.newBuilder()
                        .setFirstLineByteLength(16).setHeadersByteLength(12).build())
                .build())
            .build();
        var record = new ProducerRecord<>(TOPIC, "key-" + offset, stream.toByteArray());
        producer.send(record).get();
        log.atInfo().setMessage("Produced record offset={} conn={}").addArgument(offset).addArgument(CONN_ID).log();
    }
}
