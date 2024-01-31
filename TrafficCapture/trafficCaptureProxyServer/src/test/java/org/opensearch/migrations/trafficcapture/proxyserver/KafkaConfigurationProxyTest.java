package org.opensearch.migrations.trafficcapture.proxyserver;


import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.opensearch.migrations.testutils.SimpleHttpClientForTesting;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.URI;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;


@Slf4j
@Testcontainers(disabledWithoutDocker = true)
public class KafkaConfigurationProxyTest {
    @Container
    // see https://docs.confluent.io/platform/current/installation/versions-interoperability.html#cp-and-apache-kafka-compatibility
    private static final KafkaContainer kafkaContainer =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));


    @Container
    private static final GenericContainer<?> apacheHttpdContainer = new GenericContainer<>("httpd:2.4").withExposedPorts(80);

    private static final String HTTPD_GET_EXPECTED_RESPONSE = "<html><body><h1>It works!</h1></body></html>\n";

    private static Thread serverThread;

    final private static int listeningPort = 9124;

    @BeforeEach
    public void setUp() {
        // Start the Kafka container
        kafkaContainer.start();

        // Start the Apache HTTP Server container
        apacheHttpdContainer.start();
    }

    @AfterEach
    public void tearDown() {
        serverThread.interrupt();
        // Stop the Kafka container and Apache HTTP Server container when the tests are finished
        kafkaContainer.stop();
        apacheHttpdContainer.stop();
    }

    @Test
    public void testCaptureProxyWithKafkaAndApacheHttpd() throws IOException {
        startProxy();
        try (var client = new SimpleHttpClientForTesting()) {
            var proxyEndpoint = URI.create("http://localhost:" + listeningPort + "/");

            var response = client.makeGetRequest(proxyEndpoint, Stream.empty());
            var responseBody = new String(response.payloadBytes);
            Assertions.assertEquals(HTTPD_GET_EXPECTED_RESPONSE, responseBody);
        }
    }


    @Test
    public void testCaptureProxyWithKafkaDownBeforeStart_proxysRequest() throws IOException {
        kafkaContainer.stop();
        startProxy();

        try (var client = new SimpleHttpClientForTesting()) {
            var proxyEndpoint = URI.create("http://localhost:" + listeningPort + "/");

            var response = client.makeGetRequest(proxyEndpoint, Stream.empty());
            var responseBody = new String(response.payloadBytes);
            Assertions.assertEquals(HTTPD_GET_EXPECTED_RESPONSE, responseBody);
        }
    }


    @Test
    public void testCaptureProxyWithKafkaDownAfterStart_proxysRequest() throws IOException {
        startProxy();
        kafkaContainer.stop();

        try (var client = new SimpleHttpClientForTesting()) {
            var proxyEndpoint = URI.create("http://localhost:" + listeningPort + "/");

            var response = client.makeGetRequest(proxyEndpoint, Stream.empty());
            var responseBody = new String(response.payloadBytes);
            Assertions.assertEquals(HTTPD_GET_EXPECTED_RESPONSE, responseBody);
        }
    }

    private void startProxy() {
        // Create a thread for starting the CaptureProxy server
        serverThread = new Thread(() -> {
            try {
                // Start the CaptureProxy server
                String[] args = {
                        "--kafkaConnection", "http://" + kafkaContainer.getHost() + ":" + kafkaContainer.getFirstMappedPort(),
                        "--destinationUri", "http://" + apacheHttpdContainer.getHost() + ":" + apacheHttpdContainer.getMappedPort(80),
                        "--listenPort", String.valueOf(listeningPort),
                        "--insecureDestination"
                };

                CaptureProxy.main(args);
            } catch (Exception e) {
                throw new AssertionError("Should not have exception", e);
            }
        });

        // Start the server thread
        serverThread.start();

        try {
            Thread.sleep(500);
        } catch (Exception ignored) {
        }
    }
}
