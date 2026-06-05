package org.opensearch.migrations.replay.e2etests;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import org.opensearch.migrations.replay.TestHttpServerContext;
import org.opensearch.migrations.testutils.SharedDockerImageNames;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.testutils.ToxiProxyWrapper;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * End-to-end process-level test for the replayer's S3 tuple sink shutdown behavior.
 *
 * <p>Verifies:
 * <ol>
 *   <li>S3 uploads retry when the endpoint is unavailable (toxiproxy disabled)</li>
 *   <li>On SIGTERM, the replayer flushes all pending tuples to S3 once the endpoint is reachable</li>
 *   <li>The committed Kafka offset matches the number of tuple lines written to S3 —
 *       every consumed record was durably written before the offset advanced</li>
 *   <li>The process exits gracefully (exit code 143) — the non-daemon worker thread
 *       does not keep the JVM alive</li>
 * </ol>
 *
 * <p>Strategy:
 * <ol>
 *   <li>A background producer continuously sends traffic streams to Kafka</li>
 *   <li>A toxiproxy in front of LocalStack S3 starts DISABLED — uploads fail, retries queue</li>
 *   <li>After sufficient traffic is produced, SIGTERM is sent to the replayer</li>
 *   <li>The proxy is then enabled — retries during shutdown flush succeed</li>
 *   <li>After the process exits, assert: committed offset == tuple lines in S3</li>
 * </ol>
 *
 * <p>Launched as a subprocess so JVM-exit is a real assertion.</p>
 */
@Slf4j
@Testcontainers(disabledWithoutDocker = true)
@Tag("isolatedTest")
public class ReplayerProcessExitTest {

    private static final String TOPIC = "test-process-exit";
    private static final String GROUP_ID = "test-process-exit-group";
    private static final String BUCKET = "test-tuple-bucket";
    private static final String LOCALSTACK_HOSTNAME = "localstack-s3";
    private static final int LOCALSTACK_PORT = 4566;
    private static final int PROCESS_TIMEOUT_SECONDS = 120;
    private static final Duration PRODUCE_DURATION = Duration.ofSeconds(15);
    private static final Duration PRODUCE_INTERVAL = Duration.ofMillis(200);

    private final Network network = Network.newNetwork();

    @Container
    private final ConfluentKafkaContainer kafka =
        new ConfluentKafkaContainer(SharedDockerImageNames.KAFKA);

    @Container
    private final LocalStackContainer localstack = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:3.4"))
        .withServices(LocalStackContainer.Service.S3)
        .withNetwork(network)
        .withNetworkAliases(LOCALSTACK_HOSTNAME);

    @Test
    void replayerFlushesAllConsumedTuplesToS3OnShutdown() throws Exception {
        createS3Bucket();

        try (var s3Proxy = new ToxiProxyWrapper(network)) {
            s3Proxy.start(LOCALSTACK_HOSTNAME, LOCALSTACK_PORT);
            s3Proxy.disable();

            var random = new Random(1);
            try (var httpServer = SimpleNettyHttpServer.makeServer(
                    false, Duration.ofMinutes(10),
                    response -> TestHttpServerContext.makeResponse(random, response))) {

                var serverUri = httpServer.localhostEndpoint();
                var process = launchReplayer(serverUri.toString(), s3Proxy.getProxyUriAsString());

                // Continuously produce traffic while the replayer is running
                var producedCount = new AtomicInteger();
                var stopProducing = new AtomicBoolean(false);
                var producerThread = new Thread(() -> {
                    produceTrafficContinuously(producedCount, stopProducing);
                }, "background-producer");
                producerThread.start();

                // Let traffic flow for a while with S3 blocked
                Thread.sleep(PRODUCE_DURATION.toMillis());
                stopProducing.set(true);
                producerThread.join(5_000);

                int totalProduced = producedCount.get();
                log.info("Produced {} traffic streams to Kafka", totalProduced);
                Assertions.assertTrue(totalProduced > 0, "Should have produced at least some traffic");

                // Give replayer a moment to consume the last batch
                Thread.sleep(5_000);

                // SIGTERM → triggers shutdown hook → tupleWriter.close()
                log.info("Sending SIGTERM to replayer process (pid={})", process.toHandle().pid());
                process.destroy();

                // Enable S3 so retries during shutdown succeed
                log.info("Enabling S3 proxy — uploads should succeed during shutdown flush");
                s3Proxy.enable();

                boolean exited = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!exited) {
                    process.destroyForcibly();
                    Assertions.fail(
                        "Replayer process did not exit within " + PROCESS_TIMEOUT_SECONDS
                            + " seconds — the S3TupleSink worker thread likely kept the JVM alive");
                }

                int exitCode = process.exitValue();
                log.info("Replayer process exited with code: {}", exitCode);
                Assertions.assertEquals(143, exitCode,
                    "Expected exit code 143 (SIGTERM graceful shutdown). Got " + exitCode
                        + " — 137 would mean SIGKILL (hung), other codes indicate an internal error");

                // Check committed Kafka offset
                long committedOffset = getCommittedOffset();
                log.info("Committed Kafka offset: {}", committedOffset);
                Assertions.assertTrue(committedOffset > 0,
                    "Expected committed offset > 0 — the replayer should have committed at least some offsets");

                // Count tuple lines across all S3 objects
                int tuplesInS3 = countTupleLinesInS3();
                log.info("Tuple lines in S3: {}, committed offset: {}", tuplesInS3, committedOffset);

                Assertions.assertEquals(committedOffset, tuplesInS3,
                    "Committed Kafka offset must equal the number of tuples durably written to S3. "
                        + "A mismatch means tupleWriter.close() did not flush all pending writes "
                        + "before the offset was committed (or offsets were committed without durable writes).");
            }
        }
    }

    private void createS3Bucket() {
        try (var s3 = buildS3Client()) {
            s3.createBucket(b -> b.bucket(BUCKET));
        }
    }

    private int countTupleLinesInS3() {
        try (var s3 = buildS3Client()) {
            var response = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).build());
            int totalLines = 0;
            for (S3Object obj : response.contents()) {
                try (var gzipIn = new GZIPInputStream(
                        s3.getObject(b -> b.bucket(BUCKET).key(obj.key())))) {
                    var content = new String(gzipIn.readAllBytes(), StandardCharsets.UTF_8);
                    totalLines += content.split("\n").length;
                } catch (IOException e) {
                    log.warn("Failed to read S3 object {}: {}", obj.key(), e.getMessage());
                }
            }
            return totalLines;
        }
    }

    private long getCommittedOffset() {
        var props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (var admin = AdminClient.create(props)) {
            var offsets = admin.listConsumerGroupOffsets(GROUP_ID)
                .partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS);
            return offsets.values().stream()
                .mapToLong(OffsetAndMetadata::offset)
                .sum();
        } catch (Exception e) {
            log.warn("Failed to get committed offsets: {}", e.getMessage());
            return 0;
        }
    }

    private S3Client buildS3Client() {
        return S3Client.builder()
            .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.S3))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
            .region(Region.of(localstack.getRegion()))
            .forcePathStyle(true)
            .build();
    }

    private void produceTrafficContinuously(AtomicInteger counter, AtomicBoolean stop) {
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());

        try (var producer = new KafkaProducer<String, byte[]>(props)) {
            while (!stop.get()) {
                int i = counter.getAndIncrement();
                var ts = Timestamp.newBuilder()
                    .setSeconds(Instant.now().getEpochSecond())
                    .setNanos(0)
                    .build();

                var trafficStream = TrafficStream.newBuilder()
                    .setConnectionId("conn-" + i)
                    .setNodeId("node1")
                    .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                        .setRead(ReadObservation.newBuilder()
                            .setData(ByteString.copyFrom(
                                ("GET /" + i + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                                    .getBytes(StandardCharsets.UTF_8)))))
                    .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                        .setEndOfMessageIndicator(EndOfMessageIndication.newBuilder()
                            .setFirstLineByteLength(14)
                            .setHeadersByteLength(42)))
                    .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                        .setWrite(WriteObservation.newBuilder()
                            .setData(ByteString.copyFrom(
                                "HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.UTF_8)))))
                    .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                        .setClose(CloseObservation.getDefaultInstance()))
                    .build();

                producer.send(new ProducerRecord<>(TOPIC, trafficStream.toByteArray()));
                try {
                    Thread.sleep(PRODUCE_INTERVAL.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            producer.flush();
        }
    }

    private Process launchReplayer(String targetUri, String s3ProxyEndpoint) throws IOException {
        String classpath = System.getProperty("java.class.path");
        String javaHome = System.getProperty("java.home");
        String javaExecutable = javaHome + File.separator + "bin" + File.separator + "java";

        String[] args = {
            javaExecutable,
            "-cp", classpath,
            "org.opensearch.migrations.replay.TrafficReplayer",
            targetUri,
            "--kafka-traffic-brokers", kafka.getBootstrapServers(),
            "--kafka-traffic-topic", TOPIC,
            "--kafka-traffic-group-id", GROUP_ID,
            "--tuple-s3-bucket", BUCKET,
            "--tuple-s3-region", localstack.getRegion(),
            "--tuple-s3-endpoint", s3ProxyEndpoint,
            "--speedup-factor", "10",
            "-t", "5",
        };

        log.info("Launching replayer: {}", Arrays.toString(args));
        var pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        pb.environment().put("AWS_ACCESS_KEY_ID", localstack.getAccessKey());
        pb.environment().put("AWS_SECRET_ACCESS_KEY", localstack.getSecretKey());
        var process = pb.start();

        var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        new Thread(() -> {
            String line;
            try {
                while ((line = reader.readLine()) != null) {
                    log.info("[replayer-pid-{}]: {}", process.toHandle().pid(), line);
                }
            } catch (IOException e) {
                // process ended
            }
        }, "replayer-output-reader").start();

        return process;
    }
}
