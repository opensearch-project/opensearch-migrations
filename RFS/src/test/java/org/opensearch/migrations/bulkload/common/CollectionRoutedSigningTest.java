package org.opensearch.migrations.bulkload.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.common.bulk.operations.IndexOperationMeta;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.common.http.SigV4AuthTransformer;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.testutils.HttpRequest;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

/**
 * Sends requests through the real client, RestClient and SigV4 transformer to a local server, then
 * checks that the collection header arrived and that the signature covers it.
 */
class CollectionRoutedSigningTest {
    private static final String ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final String COLLECTION_HEADER = ServerlessCollectionRouting.COLLECTION_NAME_HEADER;

    private record Received(String verb, String path, Map<String, List<String>> headers) {}

    private final List<Received> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setCredentials() {
        System.setProperty("aws.accessKeyId", ACCESS_KEY);
        System.setProperty("aws.secretAccessKey", SECRET_KEY);
    }

    @AfterEach
    void clearCredentials() {
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
    }

    private SimpleHttpResponse respond(HttpRequest request) {
        var headers = new LinkedHashMap<String, List<String>>();
        request.getHeaders().forEach(h -> headers.computeIfAbsent(h.getKey().toLowerCase(), k -> new ArrayList<>()).add(h.getValue()));
        received.add(new Received(request.getVerb(), request.getPath().toString(), headers));
        // Index existence checks see a missing index; everything else succeeds
        var status = request.getVerb().equals("GET") ? 404 : 200;
        var body = request.getPath().toString().endsWith("_bulk")
            ? "{\"took\":1,\"errors\":false,\"items\":[]}"
            : "{}";
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        return new SimpleHttpResponse(
            Map.of("Content-Type", "application/json", "content-length", String.valueOf(bytes.length)),
            bytes, status == 200 ? "OK" : "Not Found", status);
    }

    private static OpenSearchClient routedClient(int port) {
        var connectionContext = ConnectionContextTestParams.builder()
            .host("http://localhost:" + port)
            .awsRegion("us-east-1")
            .awsServiceSigningName("aoss")
            .collectionRouted(true)
            .build()
            .toConnectionContext();
        return new OpenSearchClient_OS_2_11(new RestClient(connectionContext), new FailedRequestsLogger(),
            Version.fromString("OS 2.11"), CompressionMode.UNCOMPRESSED);
    }

    /** Re-signs a received request the way an AWS endpoint would verify it. */
    private static String recomputeAuthorization(Received request, byte[] body) {
        var amzDate = LocalDateTime.parse(request.headers().get("x-amz-date").get(0), AMZ_DATE).toInstant(ZoneOffset.UTC);
        var transformer = new SigV4AuthTransformer(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)),
            "aoss", "us-east-1", "HTTP", () -> Clock.fixed(amzDate, ZoneOffset.UTC));
        var headers = new LinkedHashMap<>(request.headers());
        headers.remove("authorization");
        var signed = transformer.transform(request.verb(), request.path().substring(1), headers,
            body == null ? Mono.empty() : Mono.just(ByteBuffer.wrap(body))).block();
        return signed.getHeaders().entrySet().stream()
            .filter(e -> e.getKey().equalsIgnoreCase("authorization"))
            .findFirst().orElseThrow().getValue().get(0);
    }

    private static void assertSignedFor(Received request, byte[] body, String collection) {
        assertThat(request.headers().get(COLLECTION_HEADER), equalTo(List.of(collection)));
        var authorization = request.headers().get("authorization").get(0);
        assertThat(authorization, containsString("x-amz-aoss-collection-name"));
        assertThat(recomputeAuthorization(request, body), equalTo(authorization));

        var tampered = new Received(request.verb(), request.path(), new LinkedHashMap<>(request.headers()));
        tampered.headers().put(COLLECTION_HEADER, List.of(collection + "-other"));
        assertThat(recomputeAuthorization(tampered, body), not(equalTo(authorization)));
    }

    private static BulkOperationSpec bulkDoc() {
        return IndexOp.builder()
            .operation(IndexOperationMeta.builder().id("1").index("tenant-a-2024").build())
            .document(Map.of("f", "v"))
            .build();
    }

    @Test
    void bulkRequest_signsCollectionHeaderOnTheWire() throws Exception {
        try (var server = SimpleNettyHttpServer.makeServer(false, this::respond)) {
            routedClient(server.port).sendBulkRequest("tenant-a-2024", List.of(bulkDoc()), null,
                false, DocumentExceptionAllowlist.empty(), "tenant-a").block();
        }

        assertThat(received, hasSize(1));
        var bulk = received.get(0);
        assertThat(bulk.path(), equalTo("/tenant-a-2024/_bulk"));
        var body = BulkNdjson.toBulkNdjsonBytes(List.of(bulkDoc()), ObjectMapperFactory.createDefaultMapper());
        assertSignedFor(bulk, body, "tenant-a");
    }

    @Test
    void createIndex_signsCollectionHeaderOnCheckAndCreate() throws Exception {
        var settings = new ObjectMapper().createObjectNode().put("settings", "{}");
        try (var server = SimpleNettyHttpServer.makeServer(false, this::respond)) {
            routedClient(server.port).createIndex("logs", settings, null, "common");
        }

        assertThat(received, hasSize(2));
        assertSignedFor(received.get(0), null, "common");
        assertSignedFor(received.get(1), settings.toString().getBytes(StandardCharsets.UTF_8), "common");
    }
}
