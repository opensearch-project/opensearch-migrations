package org.opensearch.migrations.reindexer.faileddocumentstream;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Real-S3 round-trip for {@link S3FailedDocumentStreamSink} against LocalStack: exercises the actual
 * {@link S3FailedDocumentStreamSink#s3ClientUploader} → AWS SDK {@code PutObject} path (temp-file
 * staging via {@code AsyncRequestBody.fromFile}, content-type) and reads the objects back to confirm
 * key layout and gzipped NDJSON content — none of which the in-process uploader in
 * {@code S3FailedDocumentStreamSinkTest} covers.
 */
@Tag("isolatedTest")
@Testcontainers
class S3FailedDocumentStreamSinkLocalStackTest {

    private static final String BUCKET = "rfs-failed-document-stream-localstack-test";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Container
    static final LocalStackContainer LOCAL_STACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.3.0"))
        .withServices(LocalStackContainer.Service.S3);

    private static S3AsyncClient s3;

    @BeforeAll
    static void setUp() {
        s3 = S3AsyncClient.builder()
            .endpointOverride(LOCAL_STACK.getEndpoint())
            .region(Region.of(LOCAL_STACK.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCAL_STACK.getAccessKey(), LOCAL_STACK.getSecretKey())))
            // LocalStack needs path-style addressing (no per-bucket virtual hosts).
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build()).join();
    }

    @AfterAll
    static void tearDown() {
        if (s3 != null) {
            s3.close();
        }
    }

    @Test
    void writesRealGzippedObjectsPerIndexWithExpectedKeyLayoutAndContent() throws Exception {
        var sink = S3FailedDocumentStreamSink.builder()
            .bucket(BUCKET)
            .prefix("rfs-failed-document-stream/")
            .sessionId("sess-LS")
            .workerId("worker-1")
            .region(LOCAL_STACK.getRegion())
            .uploader(S3FailedDocumentStreamSink.s3ClientUploader(s3))   // the REAL uploader, not an in-process stub
            .build();

        sink.write(record("movies", "m-1")).block();
        sink.write(record("books", "b-1")).block();
        sink.flush().block();
        sink.close();

        var sessionPrefix = "rfs-failed-document-stream/session=sess-LS/";
        var objects = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(BUCKET).prefix(sessionPrefix).build())
            .join().contents();

        // One object per target index (movies, books), each a gzipped NDJSON file.
        assertThat(objects, hasSize(2));
        var keys = objects.stream().map(S3Object::key).toList();
        var moviesKey = onlyKeyContaining(keys, "/index=movies/");
        var booksKey = onlyKeyContaining(keys, "/index=books/");
        for (var key : List.of(moviesKey, booksKey)) {
            assertThat(key, containsString(sessionPrefix));
            assertThat(key, containsString("/worker=worker-1/failed-document-stream-"));
            assertThat(key, containsString(".ndjson.gz"));
        }

        // Content-type set by the uploader.
        var contentType = s3.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(moviesKey).build())
            .join().contentType();
        assertThat(contentType, equalTo("application/gzip"));

        // The object decompresses to the record we wrote.
        var movies = readGzippedRecords(moviesKey);
        assertThat(movies, hasSize(1));
        assertThat(movies.get(0).path("targetIndex").asText(), equalTo("movies"));
        assertThat(movies.get(0).path("documentId").asText(), equalTo("m-1"));
        assertThat(movies.get(0).path("failureClass").asText(), equalTo("NON_RETRYABLE"));
    }

    private static String onlyKeyContaining(List<String> keys, String fragment) {
        var matches = keys.stream().filter(k -> k.contains(fragment)).toList();
        assertThat("exactly one object for " + fragment, matches, hasSize(1));
        return matches.get(0);
    }

    private List<JsonNode> readGzippedRecords(String key) throws Exception {
        var bytes = s3.getObject(GetObjectRequest.builder().bucket(BUCKET).key(key).build(),
                AsyncResponseTransformer.toBytes())
            .join().asByteArray();
        String decoded;
        try (var gz = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            decoded = new String(gz.readAllBytes());
        }
        var out = new java.util.ArrayList<JsonNode>();
        for (var line : decoded.strip().split("\n")) {
            if (!line.isBlank()) {
                out.add(MAPPER.readTree(line));
            }
        }
        return out;
    }

    private static FailedDocumentStreamRecord record(String index, String docId) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("op", "index").put("_id", docId);
        ObjectNode resp = MAPPER.createObjectNode();
        resp.putObject("index").put("_id", docId).put("status", 400);
        return FailedDocumentStreamRecord.builder()
            .sessionId("sess-LS").workerId("worker-1").targetIndex(index).documentId(docId)
            .failureType("mapper_parsing_exception").failureClass(FailureClass.NON_RETRYABLE)
            .timestamp(Instant.parse("2026-05-14T12:00:00Z").toString())
            .requestItem(req).responseItem(resp)
            .build();
    }
}
