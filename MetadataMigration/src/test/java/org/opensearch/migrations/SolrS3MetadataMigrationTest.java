package org.opensearch.migrations;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * End-to-end test of the SOLR → S3 → MetadataMigration → OpenSearch pipeline
 * exercising the {@link org.opensearch.migrations.cli.ClusterReaderExtractor}
 * S3 branch. This is the code path that runs in the
 * {@code migration-workflow-runmetadata} pod when the snapshot is produced by
 * the upstream CreateSnapshot step and stored in S3.
 *
 * <p>Why this test exists: prior unit-style tests for the S3 marker helpers
 * (TestCreateSnapshotSolrS3) and the file-system-backed MetadataMigration
 * (SolrMetadataMigrationTest) together still missed a layout mismatch in the
 * reader-side discovery logic, because neither of them ran MetadataMigration
 * against real S3 data produced by CreateSnapshot. This test closes that gap
 * by wiring the real producer to the real consumer over LocalStack.
 *
 * <p>Layout exercised (two-level, Solr's S3BackupRepository incremental
 * backup format):
 * <pre>
 *   s3://bucket/[subpath/]&lt;snapshotName&gt;/
 *       &lt;backupName&gt;/                     &lt;-- outer, == backup "name=" arg
 *           &lt;collection&gt;/                 &lt;-- inner, Solr per-collection data dir
 *               zk_backup_N/              &lt;-- schema + config (discovered via regex)
 *               shard_backup_metadata/
 *               backup_0.properties
 *               index/
 * </pre>
 *
 * <p>Tagged {@code isolatedTest} because it spins up three containers
 * (LocalStack, SolrCloud, OpenSearch) and runs for ~60s.
 */
@Slf4j
@Tag("isolatedTest")
@Testcontainers
class SolrS3MetadataMigrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BUCKET_NAME = "solr-meta-s3-test";
    private static final String REGION = "us-east-1";
    private static final String LOCALSTACK_ALIAS = "localstack";

    static {
        // LocalStack accepts any non-empty credentials, but production code uses
        // DefaultCredentialsProvider. CI runners have no ambient creds, so seed
        // sysprops here before the SDK's credential chain is first consulted.
        if (System.getProperty("aws.accessKeyId") == null) {
            System.setProperty("aws.accessKeyId", "test");
        }
        if (System.getProperty("aws.secretAccessKey") == null) {
            System.setProperty("aws.secretAccessKey", "test");
        }
        if (System.getProperty("aws.region") == null) {
            System.setProperty("aws.region", REGION);
        }
    }

    static final Network NETWORK = Network.newNetwork();

    @Container
    static final LocalStackContainer LOCAL_STACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:4.3.0"))
        .withServices(LocalStackContainer.Service.S3)
        .withNetwork(NETWORK)
        .withNetworkAliases(LOCALSTACK_ALIAS);

    @Container
    static final GenericContainer<?> SOLR_CLOUD = new GenericContainer<>(
            DockerImageName.parse("solr:8.11.4"))
        .withNetwork(NETWORK)
        .withExposedPorts(8983)
        .withCopyFileToContainer(
            MountableFile.forClasspathResource("solr-s3-backup.xml"),
            "/var/solr/data/solr.xml")
        .withEnv("AWS_ACCESS_KEY_ID", "test")
        .withEnv("AWS_SECRET_ACCESS_KEY", "test")
        .withEnv("SOLR_OPTS",
            "-DS3_BUCKET_NAME=" + BUCKET_NAME
                + " -DS3_REGION=" + REGION
                + " -DS3_ENDPOINT=http://" + LOCALSTACK_ALIAS + ":4566")
        .withEnv("SOLR_SECURITY_MANAGER_ENABLED", "false")
        .withCreateContainerCmdModifier(cmd -> cmd.withUser("root"))
        .withCommand("bash", "-c",
            "cp /opt/solr/dist/solr-s3-repository-*.jar "
                + "/opt/solr/contrib/s3-repository/lib/ && "
                + "exec solr -c -f -force")
        .waitingFor(Wait.forHttp("/solr/admin/collections?action=LIST&wt=json")
            .forPort(8983)
            .forStatusCode(200)
            .withStartupTimeout(Duration.ofMinutes(3)));

    @BeforeAll
    static void createBucket() throws Exception {
        LOCAL_STACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_NAME);
        log.info("Created S3 bucket '{}' in LocalStack", BUCKET_NAME);
    }

    @AfterEach
    void deleteAllCollections() throws Exception {
        var listResp = SOLR_CLOUD.execInContainer("curl", "-sf",
            "http://localhost:8983/solr/admin/collections?action=LIST&wt=json");
        var body = listResp.getStdout();
        int idx = body.indexOf("\"collections\":[");
        if (idx < 0) {
            return;
        }
        int start = body.indexOf('[', idx);
        int end = body.indexOf(']', start);
        if (start < 0 || end < 0 || end <= start + 1) {
            return;
        }
        String inner = body.substring(start + 1, end).trim();
        if (inner.isEmpty()) {
            return;
        }
        for (String raw : inner.split(",")) {
            String name = raw.trim().replaceAll("^\"|\"$", "");
            if (name.isEmpty()) {
                continue;
            }
            SOLR_CLOUD.execInContainer("curl", "-sf",
                "http://localhost:8983/solr/admin/collections?action=DELETE&name=" + name + "&wt=json");
            log.info("Deleted SolrCloud collection '{}'", name);
        }
    }

    @TempDir
    java.io.File tempDir;

    /**
     * Full pipeline: SolrCloud → CreateSnapshot → S3 (LocalStack) →
     * MetadataMigration (S3 branch) → OpenSearch.
     *
     * Had this existed, Jenkins build #256 would have failed locally with:
     *   "Failed to list backup directory: /tmp/&lt;collection&gt;"
     * instead of the production pod exiting 120 silently.
     */
    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void solrCloudS3BackupProducesCorrectOpenSearchMappings() throws Exception {
        String collName = "s3_meta_coll";
        createCollectionWithSchema(collName);
        indexOneDoc(collName);

        String snapshotName = "meta_s3_snap";
        String subpath = "v1";
        String s3RepoUri = "s3://" + BUCKET_NAME + "/" + subpath;

        // 1. Run CreateSnapshot → writes real Solr S3BackupRepository layout to LocalStack
        var createArgs = new CreateSnapshot.Args();
        createArgs.sourceArgs.host = solrUrl();
        createArgs.sourceArgs.insecure = true;
        createArgs.sourceType = "solr";
        createArgs.snapshotName = snapshotName;
        createArgs.snapshotRepoName = "s3";
        createArgs.s3RepoUri = s3RepoUri;
        createArgs.s3Region = REGION;
        createArgs.s3Endpoint = localStackEndpoint();
        createArgs.solrCollections = List.of(collName);
        createArgs.noWait = false;

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        new CreateSnapshot(createArgs, snapshotContext.createSnapshotCreateContext()).run();
        log.info("CreateSnapshot finished — S3 at {}/{}", s3RepoUri, snapshotName);

        // 2. Bring up OpenSearch target (no shared Docker network needed — SDK talks to it via mapped port)
        try (var target = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            CompletableFuture.runAsync(target::start).join();
            var targetOps = new ClusterOperations(target);

            // 3. Run MetadataMigration exercising the S3 branch in ClusterReaderExtractor
            var s3LocalDir = tempDir.toPath().resolve("s3-download");
            Files.createDirectories(s3LocalDir);

            var metaArgs = new MigrateOrEvaluateArgs();
            metaArgs.sourceVersion = Version.fromString("SOLR 8.11.4");
            metaArgs.snapshotName = snapshotName;
            metaArgs.s3RepoUri = s3RepoUri;
            metaArgs.s3Region = REGION;
            metaArgs.s3Endpoint = localStackEndpoint();
            metaArgs.s3LocalDirPath = s3LocalDir.toString();
            metaArgs.targetArgs.host = target.getUrl();

            var metadataContext = MetadataMigrationTestContext.factory().noOtelTracking();
            var result = new MetadataMigration().migrate(metaArgs).execute(metadataContext);

            log.atInfo().setMessage("Migration result: {}").addArgument(result.asCliOutput()).log();
            assertThat("Migration must succeed (the bug that caused Jenkins #256 should surface here as non-zero exit)",
                result.getExitCode(), equalTo(0));

            // 4. Verify target index mappings — full correctness, not just "no crash"
            var res = targetOps.get("/" + collName + "/_mapping");
            assertThat("Target mapping endpoint should return 200", res.getKey(), equalTo(200));

            var mappings = MAPPER.readTree(res.getValue());
            var properties = mappings.path(collName).path("mappings").path("properties");

            assertThat("title → keyword", properties.path("title").path("type").asText(), equalTo("keyword"));
            assertThat("count → integer", properties.path("count").path("type").asText(), equalTo("integer"));
            assertThat("created → date", properties.path("created").path("type").asText(), equalTo("date"));
            assertThat("description → text", properties.path("description").path("type").asText(), equalTo("text"));
            assertThat("active → boolean", properties.path("active").path("type").asText(), equalTo("boolean"));
        }
    }

    // ---- helpers ----

    private static String solrUrl() {
        return "http://" + SOLR_CLOUD.getHost() + ":" + SOLR_CLOUD.getMappedPort(8983);
    }

    private static String localStackEndpoint() {
        return LOCAL_STACK.getEndpoint().toString();
    }

    private void createCollectionWithSchema(String name) throws Exception {
        SOLR_CLOUD.execInContainer("curl", "-sf",
            "http://localhost:8983/solr/admin/collections?action=CREATE"
                + "&name=" + name
                + "&numShards=1"
                + "&replicationFactor=1"
                + "&maxShardsPerNode=1"
                + "&wt=json");
        addField(name, "title", "string");
        addField(name, "count", "pint");
        addField(name, "created", "pdate");
        addField(name, "description", "text_general");
        addField(name, "active", "boolean");
        log.info("Created collection '{}' with schema fields", name);
    }

    private void addField(String collection, String fieldName, String type) throws Exception {
        SOLR_CLOUD.execInContainer("curl", "-sf",
            "http://localhost:8983/solr/" + collection + "/schema",
            "-H", "Content-Type: application/json",
            "-d", "{\"add-field\":{\"name\":\"" + fieldName + "\",\"type\":\"" + type + "\",\"stored\":true}}");
    }

    private void indexOneDoc(String collection) throws Exception {
        SOLR_CLOUD.execInContainer("curl", "-sf",
            "http://localhost:8983/solr/" + collection + "/update?commit=true",
            "-H", "Content-Type: application/json",
            "-d", "[{\"id\":\"1\",\"title\":\"test\",\"count\":42,"
                + "\"created\":\"2024-01-01T00:00:00Z\","
                + "\"description\":\"hello world\",\"active\":true}]");
        log.info("Indexed 1 doc into '{}'", collection);
    }
}
