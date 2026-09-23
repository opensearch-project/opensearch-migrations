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
import org.apache.kafka.clients.consumer.ConsumerConfig;
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
        var kafka = new org.testcontainers.kafka.ConfluentKafkaContainer(SharedDockerImageNames.KAFKA);
        kafka.start();
        SimpleNettyHttpServer destination = null;
        CaptureProxyContainer proxy = null;
        try {
            createTrafficTopic(stripScheme(kafka.getBootstrapServers()), topic, partitions);
            var body = DESTINATION_BODY.getBytes(StandardCharsets.UTF_8);
            // Content-Length matters: without it the client reads until EOF, and since the proxy keeps the
            // connection alive that is a hang rather than a response.
            destination = SimpleNettyHttpServer.makeServer(false, request -> new SimpleHttpResponse(
                Map.of("Content-Type", "text/plain", "Content-Length", String.valueOf(body.length)),
                body,
                "OK",
                200
            ));
            var destinationUri = destination.localhostEndpoint().toString();
            var brokers = stripScheme(kafka.getBootstrapServers());
            proxy = new CaptureProxyContainer(
                () -> destinationUri,
                () -> brokers,
                Stream.of("--kafkaTopic", topic)
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
