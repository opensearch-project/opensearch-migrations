package org.opensearch.migrations.bulkload.common;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.common.http.TestTlsCredentialsProvider;
import org.opensearch.migrations.bulkload.common.http.TestTlsUtils;
import org.opensearch.migrations.bulkload.tracing.RfsContexts;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.testutils.HttpRequest;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

class RestClientTest {

    private static final int SEND_CREATE_GET_SNAPSHOT_SIZE = 82;
    private static final int SEND_CREATE_SNAPSHOT_SIZE = 139;
    private static final int SEND_TOTAL_SIZE = SEND_CREATE_GET_SNAPSHOT_SIZE + SEND_CREATE_SNAPSHOT_SIZE;
    private static final int READ_RESPONSE_SIZE = 66;
    private static final int READ_TOTAL_SIZE = READ_RESPONSE_SIZE + READ_RESPONSE_SIZE;

    private static TestTlsUtils.CertificateBundle caCertBundle;
    private static TestTlsUtils.CertificateBundle serverCertBundle;
    private static TestTlsUtils.CertificateBundle clientCertBundle;

    private static HttpClient makeSingleConnectionHttpClient() {
        var provider = ConnectionProvider.builder("singleConnection").maxConnections(1).build();
        return HttpClient.create(provider);
    }

     @BeforeAll
    static void setupCertificates() throws Exception {
        caCertBundle = TestTlsUtils.generateCaCertificate();
        serverCertBundle = TestTlsUtils.generateServerCertificate(caCertBundle);
        clientCertBundle = TestTlsUtils.generateClientCertificate(caCertBundle);
    }


    @Test
    public void testGetEmitsInstrumentation() throws Exception {
        var rootContext = SnapshotTestContext.factory().withAllTracking();
        try (var testServer = SimpleNettyHttpServer.makeServer(false, null, this::makeResponseContext)) {
            var restClient = new RestClient(ConnectionContextTestParams.builder()
                .host("http://localhost:" + testServer.port)
                .build()
                .toConnectionContext(), makeSingleConnectionHttpClient());
            try (var topScope = rootContext.createSnapshotCreateContext()) {
                restClient.postAsync("/", "empty", topScope.createSnapshotContext()).block();
                restClient.getAsync("/", topScope.createGetSnapshotContext()).block();
            }
        }

        Thread.sleep(200);
        var allMetricData = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();

        for (var kvp : Map.of(
            "createGetSnapshotContext",
            new long[] { SEND_CREATE_GET_SNAPSHOT_SIZE, READ_RESPONSE_SIZE },
            "createSnapshotContext",
            new long[] { SEND_CREATE_SNAPSHOT_SIZE, READ_RESPONSE_SIZE },
            "",
            new long[] { SEND_TOTAL_SIZE, READ_TOTAL_SIZE }
        ).entrySet()) {
            long bytesSent = allMetricData.stream()
                .filter(md -> md.getName().startsWith("bytesSent"))
                .reduce((a, b) -> b)
                .get()
                .getLongSumData()
                .getPoints()
                .stream()
                .filter(
                    pd -> pd.getAttributes()
                        .asMap()
                        .values()
                        .stream()
                        .map(o -> (String) o)
                        .collect(Collectors.joining())
                        .equals(kvp.getKey())
                )
                .reduce((a, b) -> b)
                .get()
                .getValue();
            long bytesRead = allMetricData.stream()
                .filter(md -> md.getName().startsWith("bytesRead"))
                .reduce((a, b) -> b)
                .get()
                .getLongSumData()
                .getPoints()
                .stream()
                .filter(
                    pd -> pd.getAttributes()
                        .asMap()
                        .values()
                        .stream()
                        .map(o -> (String) o)
                        .collect(Collectors.joining())
                        .equals(kvp.getKey())
                )
                .reduce((a, b) -> b)
                .get()
                .getValue();
            MatcherAssert.assertThat(
                "Checking bytes {send, read} for context '" + kvp.getKey() + "'",
                new long[] { bytesSent, bytesRead },
                Matchers.equalTo(kvp.getValue())
            );
        }

        final var finishedSpans = rootContext.inMemoryInstrumentationBundle.getFinishedSpans();
        final var finishedSpanNames = finishedSpans.stream().map(SpanData::getName).collect(Collectors.toList());
        MatcherAssert.assertThat(
            finishedSpanNames,
            Matchers.containsInAnyOrder("httpRequest", "httpRequest", "createSnapshot")
        );

        final var httpRequestSpansByTime = finishedSpans.stream()
            .filter(sd -> sd.getName().equals("httpRequest"))
            .sorted(Comparator.comparing(SpanData::getEndEpochNanos))
            .collect(Collectors.toList());
        int i = 0;
        for (var expectedBytes : List.of(new long[] { SEND_CREATE_SNAPSHOT_SIZE, READ_RESPONSE_SIZE }, new long[] { SEND_CREATE_GET_SNAPSHOT_SIZE, READ_RESPONSE_SIZE })) {
            var span = httpRequestSpansByTime.get(i++);
            long bytesSent = span.getAttributes().get(RfsContexts.GenericRequestContext.BYTES_SENT_ATTR);
            long bytesRead = span.getAttributes().get(RfsContexts.GenericRequestContext.BYTES_READ_ATTR);
            MatcherAssert.assertThat(
                "Checking bytes {send, read} for httpRequest " + i,
                new long[] { bytesSent, bytesRead },
                Matchers.equalTo(expectedBytes)
            );
        }
    }

    @Test
    public void testMutualTlsSuccess() throws Exception {
        SslContext serverSslContext = SslContextBuilder
                .forServer(serverCertBundle.getCertificateInputStream(),
                    serverCertBundle.getPrivateKeyInputStream())
                .trustManager(caCertBundle.getCertificateInputStream())
                .clientAuth(ClientAuth.REQUIRE)
                .build();

        SimpleNettyHttpServer.SSLEngineSupplier engineSupplier = (allocator) -> {
            SSLEngine engine = serverSslContext.newEngine(allocator);
            engine.setUseClientMode(false);
            return engine;
        };

        try (var testServer = SimpleNettyHttpServer.makeServer(
                engineSupplier,
                null,
                this::makeResponseContext)) {

            var params = ConnectionContextTestParams.builder()
                    .host("https://localhost:" + testServer.port)
                    .insecure(false)
                    .build();

            var connCtx = params.toConnectionContext();
            connCtx.setTlsCredentialsProvider(
                new TestTlsCredentialsProvider(caCertBundle, clientCertBundle)
            );

            var restClient = new RestClient(connCtx);

            var response = restClient.get("/", null);

            Assertions.assertEquals(200, response.statusCode);
            Assertions.assertEquals("Hi", response.body);
        }
    }

    @Test
    public void testMutualTlsFailsWithoutClientCert() throws Exception {
        SslContext serverSslContext = SslContextBuilder
                .forServer(serverCertBundle.getCertificateInputStream(),
                    serverCertBundle.getPrivateKeyInputStream())
                .trustManager(caCertBundle.getCertificateInputStream())
                .clientAuth(ClientAuth.REQUIRE)
                .build();

        SimpleNettyHttpServer.SSLEngineSupplier engineSupplier = (allocator) -> {
            SSLEngine engine = serverSslContext.newEngine(allocator);
            engine.setUseClientMode(false);
            return engine;
        };

        try (var testServer = SimpleNettyHttpServer.makeServer(
                engineSupplier,
                null,
                this::makeResponseContext)) {

            var params = ConnectionContextTestParams.builder()
                    .host("https://localhost:" + testServer.port)
                    .insecure(false)
                    .build();

            // Set the TLS credentials provider directly with only CA cert
            var connCtx = params.toConnectionContext();
            connCtx.setTlsCredentialsProvider(
                new TestTlsCredentialsProvider(caCertBundle, null)
            );

            var restClient = new RestClient(params.toConnectionContext());

            // 1) Capture the top-level exception (ReactiveException)
            Exception ex = Assertions.assertThrows(Exception.class, () -> restClient.get("/", null));

            // 2) Unwrap the cause chain
            Throwable cause = ex.getCause();
            while (cause != null && !(cause instanceof SSLException)) {
                cause = cause.getCause();
            }

            // 3) Now check cause is the type we want
            Assertions.assertNotNull(cause, "Expected an SSLException somewhere in the cause chain");
            Assertions.assertTrue(cause instanceof SSLHandshakeException,
                    "Expected an SSLHandshakeException but got " + cause.getClass());
        }
    }

    SimpleHttpResponse makeResponseContext(HttpRequest firstLine) {
        var payloadBytes = "Hi".getBytes(StandardCharsets.UTF_8);
        return new SimpleHttpResponse(
            Map.of("Content-Type", "text/plain", "content-length", payloadBytes.length + ""),
            payloadBytes,
            "OK",
            200
        );
    }
}
