/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafka;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.testutils.SharedDockerImageNames;
import org.opensearch.migrations.testutils.SimpleHttpClientForTesting;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.trafficcapture.proxyserver.testcontainers.CaptureProxyContainer;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;

import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;

/**
 * A capture topic written by the real, unmodified capture proxy.
 *
 * <p>The point is that the topic is one the real proxy produced, not one a test wrote to look like it. A
 * hand-rolled writer would encode this module's own belief about the wire format and so could never
 * detect that belief drifting from the producer.
 *
 * <p>Three things start, in dependency order: a Kafka broker, an in-process destination server, and
 * {@code CaptureProxyContainer}, which runs the actual {@code CaptureProxy.main} on a thread. Traffic
 * driven through {@link #sendGet} reaches the destination and is captured to the topic as a side
 * effect, exactly as in production.
 *
 * <p>It deliberately stops at raw record bytes. Decoding is the dumper's job, and a fixture that also
 * decoded would be a second implementation of the thing under test — so a test asserting the envelope
 * shape and a test asserting the dumper's output stay independent claims.
 *
 * <p>Requires Docker, so callers are tagged {@code isolatedTest}. Each instance owns its own broker
 * rather than sharing a static one, so two of these never collide.
 */
public final class ProxyWrittenTopic implements AutoCloseable {
    private static final String DESTINATION_BODY = "it works";
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);

    private final org.testcontainers.kafka.ConfluentKafkaContainer kafka;
    private final SimpleNettyHttpServer destination;
    private final CaptureProxyContainer proxy;
    private final String topic;

    private ProxyWrittenTopic(
        org.testcontainers.kafka.ConfluentKafkaContainer kafka,
        SimpleNettyHttpServer destination,
        CaptureProxyContainer proxy,
        String topic
    ) {
        this.kafka = kafka;
        this.destination = destination;
        this.proxy = proxy;
        this.topic = topic;
    }

    /**
     * Starts a broker, a destination, and the real proxy wired between them. The topic is passed to the
     * proxy with {@code --kafkaTopic} rather than relying on its default, so concurrent runs cannot read
     * each other's records.
     */
    public static ProxyWrittenTopic start(String topic) throws Exception {
        return start(topic, 1);
    }

    /**
     * @param partitions more than one is what G2 needs to exercise per-partition demand and revocation
     */
    public static ProxyWrittenTopic start(String topic, int partitions) throws Exception {
        var body = DESTINATION_BODY.getBytes(StandardCharsets.UTF_8);
        return start(
            topic,
            partitions,
            List.of(new SimpleHttpResponse(
                Map.of("Content-Type", "text/plain", "Content-Length", String.valueOf(body.length)),
                body,
                "OK",
                200
            ))
        );
    }

    /** Starts the real proxy against a source server that emits the supplied response sequence. */
    public static ProxyWrittenTopic startWithSourceResponses(
        String topic,
        List<SimpleHttpResponse> sourceResponses
    ) throws Exception {
        return start(topic, 1, List.copyOf(sourceResponses));
    }

    private static ProxyWrittenTopic start(
        String topic,
        int partitions,
        List<SimpleHttpResponse> sourceResponses
    ) throws Exception {
        var kafka = new org.testcontainers.kafka.ConfluentKafkaContainer(SharedDockerImageNames.KAFKA);
        kafka.start();
        SimpleNettyHttpServer destination = null;
        CaptureProxyContainer proxy = null;
        try {
            createTrafficTopic(stripScheme(kafka.getBootstrapServers()), topic, partitions);
            destination = SimpleNettyHttpServer.makeServerWithResponses(
                false,
                request -> sourceResponses
            );
            var destinationUri = destination.localhostEndpoint().toString();
            var brokers = stripScheme(kafka.getBootstrapServers());
            proxy = new CaptureProxyContainer(
                () -> destinationUri,
                () -> brokers,
                // A one-second heartbeat, so a test can wait for a WriterPartitionHeartbeat without sitting out
                // the production interval. The heartbeat is one of the three payload cases the replayer must
                // decode, so it has to be observable here rather than assumed.
                Stream.of("--kafkaTopic", topic, "--heartbeat-interval-seconds", "1")
            );
            proxy.start();
            return new ProxyWrittenTopic(kafka, destination, proxy, topic);
        } catch (Exception | Error startupFailure) {
            closeQuietly(proxy, destination, kafka);
            throw startupFailure;
        }
    }

    /**
     * Creates the topic with {@code message.timestamp.type=LogAppendTime} before the proxy starts.
     *
     * <p>Not a tuning choice. The proxy runs a Kafka capability probe at startup and <strong>refuses to
     * serve traffic</strong> unless the topic assigns broker timestamps, so an auto-created topic — which
     * gets the broker default of {@code CreateTime} — will not start. Beyond that,
     * {@code LogAppendTime} is what makes {@code ApplicationKafkaRecord.logAppendTimeMillis} a
     * broker-assigned time rather than a producer guess, and broker-time expiration, the backward-skew
     * fatal check, and heartbeat baselines are all computed from it.
     */
    private static void createTrafficTopic(String brokers, String topic, int partitions) throws Exception {
        var adminProps = new Properties();
        adminProps.setProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        try (var admin = AdminClient.create(adminProps)) {
            var newTopic = new NewTopic(topic, partitions, (short) 1).configs(
                Map.of(TopicConfig.MESSAGE_TIMESTAMP_TYPE_CONFIG, "LogAppendTime")
            );
            admin.createTopics(List.of(newTopic)).all().get(30, TimeUnit.SECONDS);
        }
    }

    /** The broker list in the {@code host:port} form {@code --kafka-traffic-brokers} expects. */
    public String brokers() {
        return stripScheme(kafka.getBootstrapServers());
    }

    public String topic() {
        return topic;
    }

    public URI proxyEndpoint() {
        return URI.create(CaptureProxyContainer.getUriFromContainer(proxy));
    }

    /**
     * Drives one request through the proxy to the destination, which captures it to the topic as a side
     * effect. Returns the destination's status so a caller can tell a capture failure from a proxy
     * failure.
     */
    public int sendGet(String path) throws Exception {
        try (var client = new SimpleHttpClientForTesting()) {
            var response = client.makeGetRequest(proxyEndpoint().resolve(path), Stream.empty());
            return response.statusCode;
        }
    }

    /**
     * Reads raw record values from the topic, waiting until {@code atLeast} have arrived or the timeout
     * expires. Returns whatever it has rather than throwing, so a caller asserts on the count and gets a
     * useful failure message instead of a timeout exception.
     *
     * <p>Values are undecoded on purpose — see the class note.
     */
    public List<byte[]> readRecordValues(int atLeast, Duration timeout) {
        var props = new Properties();
        props.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers());
        props.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.StringDeserializer");
        props.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        props.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        var values = new ArrayList<byte[]>();
        try (var consumer = new KafkaConsumer<String, byte[]>(props)) {
            var partitions = consumer.partitionsFor(topic)
                .stream()
                .map(info -> new TopicPartition(info.topic(), info.partition()))
                .collect(Collectors.toList());
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            var deadline = System.nanoTime() + timeout.toNanos();
            while (values.size() < atLeast && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> values.add(record.value()));
            }
        }
        return values;
    }

    public List<byte[]> readRecordValues(int atLeast) {
        return readRecordValues(atLeast, DEFAULT_READ_TIMEOUT);
    }

    /**
     * Reads until {@code atLeast} records carry a {@code TrafficStream} payload, and returns those.
     *
     * <p>Use this rather than {@link #readRecordValues(int)} to wait for captured traffic. The proxy writes a
     * {@code CaptureCapabilityProbe} at startup, so "at least one record exists" is satisfied before any request
     * has been captured at all — a test gated on the count can inspect or snapshot the probe and pass while the
     * request it cares about is still in flight. Filtering on the payload case removes the race rather than
     * papering over it with a longer timeout.
     *
     * <p>Records that fail to decode are not filtered out, they are fatal: an undecodable value on this topic
     * means the proxy wrote something the replayer cannot read, which is the one thing these fixtures exist to
     * catch.
     */
    public List<byte[]> readTrafficStreamValues(int atLeast, Duration timeout) {
        var deadline = System.nanoTime() + timeout.toNanos();
        var trafficStreams = new ArrayList<byte[]>();
        while (trafficStreams.size() < atLeast && System.nanoTime() < deadline) {
            trafficStreams.clear();
            // Reads to the current end offsets rather than stopping at a count: a count-bounded read returns
            // after the first batch, which on a fresh topic is the startup probe alone, and would then never
            // see the records that arrive after it.
            for (var value : readAllRecordValues()) {
                CaptureRecord envelope;
                try {
                    envelope = CaptureRecord.parseFrom(value);
                } catch (InvalidProtocolBufferException notAnEnvelope) {
                    throw new IllegalStateException(
                        "the proxy wrote a value that is not a CaptureRecord envelope", notAnEnvelope
                    );
                }
                if (envelope.getPayloadCase() == CaptureRecord.PayloadCase.TRAFFICSTREAM) {
                    trafficStreams.add(value);
                }
            }
        }
        return trafficStreams;
    }

    public List<byte[]> readTrafficStreamValues(int atLeast) {
        return readTrafficStreamValues(atLeast, DEFAULT_READ_TIMEOUT);
    }

    /**
     * Waits until every payload case in {@code payloadCases} has appeared on the topic, and returns the cases
     * seen.
     *
     * <p>The heartbeat is the case that needs waiting for: the proxy writes one on a timer rather than in
     * response to traffic, so a test that only drives a request will not see one, and "all three payload cases
     * are decoded" would be an assumption rather than evidence. The fixture runs the proxy with a one-second
     * heartbeat interval so that wait is short.
     */
    public java.util.Set<CaptureRecord.PayloadCase> awaitPayloadCases(
        java.util.Set<CaptureRecord.PayloadCase> payloadCases,
        Duration timeout
    ) {
        var deadline = System.nanoTime() + timeout.toNanos();
        var seen = new java.util.LinkedHashSet<CaptureRecord.PayloadCase>();
        while (!seen.containsAll(payloadCases) && System.nanoTime() < deadline) {
            seen.clear();
            for (var value : readAllRecordValues()) {
                try {
                    seen.add(CaptureRecord.parseFrom(value).getPayloadCase());
                } catch (InvalidProtocolBufferException notAnEnvelope) {
                    throw new IllegalStateException(
                        "the proxy wrote a value that is not a CaptureRecord envelope", notAnEnvelope
                    );
                }
            }
        }
        return seen;
    }

    /**
     * Writes one value directly to a chosen partition, bypassing the proxy.
     *
     * <p>For the two things proxy-written traffic cannot express. Partition placement is the proxy's
     * partitioner's business, so a test that needs records on specific partitions would otherwise have to
     * <em>assume</em> the spread it got and skip when it did not — and a skip that fires is indistinguishable
     * from coverage that was never there. And a malformed value cannot be produced by a correct proxy at all,
     * while the dumper's behaviour on one is a stated protocol requirement.
     *
     * <p>This does not weaken the proxy-written claim: the envelope-shape assertions still read what the real
     * proxy wrote. What is under test here is the reader's handling of placement and of corruption, neither of
     * which depends on who produced the bytes.
     */
    public void produceDirectly(int partition, byte[] value) throws Exception {
        var props = new Properties();
        props.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers());
        props.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.StringSerializer");
        props.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.ByteArraySerializer");
        try (var producer = new KafkaProducer<String, byte[]>(props)) {
            producer.send(new ProducerRecord<>(topic, partition, null, value)).get(30, TimeUnit.SECONDS);
        }
    }

    /** Every record currently on the topic, read to the end offsets of every partition. */
    public List<byte[]> readAllRecordValues() {
        var values = new ArrayList<byte[]>();
        readToEnd((partition, record) -> values.add(record.value()));
        return values;
    }

    /** Where Kafka says a record is, independently of anything the replayer renders. */
    public record RecordLocation(int partition, long offset) {}

    /**
     * Every record's partition and offset, as Kafka reports them.
     *
     * <p>For comparing rendered metadata against the real thing. Asserting only that a dump <em>contains</em>
     * partition and offset fields passes for a dumper that prints plausible but wrong values, and on a
     * single-partition topic it passes for one that prints a constant.
     */
    public List<RecordLocation> readRecordMetadata() {
        var locations = new ArrayList<RecordLocation>();
        readToEnd((partition, record) ->
            locations.add(new RecordLocation(record.partition(), record.offset()))
        );
        return locations;
    }

    /**
     * Reads every partition from the beginning to the end offset it had when the read started.
     *
     * <p>Bounded by end offsets rather than by a record count, because a count stops at the first batch. On a
     * freshly started topic that batch is the proxy's startup capability probe on its own, so a count-bounded
     * read of "one record" can never see the captured traffic that follows it.
     */
    private void readToEnd(
        java.util.function.BiConsumer<TopicPartition, org.apache.kafka.clients.consumer.ConsumerRecord<String, byte[]>> sink
    ) {
        var props = new Properties();
        props.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers());
        props.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.StringDeserializer");
        props.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        props.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        try (var consumer = new KafkaConsumer<String, byte[]>(props)) {
            var partitions = consumer.partitionsFor(topic)
                .stream()
                .map(info -> new TopicPartition(info.topic(), info.partition()))
                .collect(Collectors.toList());
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            var endOffsets = consumer.endOffsets(partitions);
            var deadline = System.nanoTime() + DEFAULT_READ_TIMEOUT.toNanos();
            var atEnd = partitions.stream()
                .allMatch(partition -> endOffsets.getOrDefault(partition, 0L) == 0L);
            while (!atEnd && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500))
                    .forEach(record -> sink.accept(new TopicPartition(record.topic(), record.partition()), record));
                atEnd = partitions.stream()
                    .allMatch(partition -> consumer.position(partition) >= endOffsets.getOrDefault(partition, 0L));
            }
        }
    }

    @Override
    public void close() {
        closeQuietly(proxy, destination, kafka);
    }

    /**
     * {@code ConfluentKafkaContainer} reports {@code PLAINTEXT://host:port}, while both the proxy's
     * {@code --kafkaConnection} and the replayer's {@code --kafka-traffic-brokers} take a bare
     * {@code host:port}.
     */
    private static String stripScheme(String bootstrapServers) {
        var schemeEnd = bootstrapServers.indexOf("://");
        return schemeEnd < 0 ? bootstrapServers : bootstrapServers.substring(schemeEnd + 3);
    }

    /** Shuts down in reverse dependency order, letting every stage run even if an earlier one fails. */
    private static void closeQuietly(AutoCloseable... resources) {
        for (var resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Exception ignored) {
                // Nothing useful to do while tearing a fixture down; the test's own assertions decide the
                // outcome, and masking them with a cleanup failure would be worse.
            }
        }
    }
}
