package org.opensearch.migrations;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.metadata.tracing.MetadataMigrationTestContext;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
 * <p>Parameterized over Solr version × deployment mode:
 * <ul>
 *   <li>Versions: {@code 8.11.4} and {@code 9.8.1}. (Solr's S3BackupRepository, SIP-12,
 *       only exists in 8.10+, so 6/7 cannot back up to S3 — those are covered by the
 *       filesystem-based {@code SolrMetadataMigrationTest}.)</li>
 *   <li>{@link Mode#CLOUD} — SolrCloud (Collections API BACKUP). Config comes from
 *       ZooKeeper and Solr writes it into {@code zk_backup_N/configs/}.</li>
 *   <li>{@link Mode#STANDALONE} — single-node Solr (replication-handler backup). The
 *       backup itself contains only raw Lucene files (no config); CreateSnapshot fetches
 *       each core's {@code managed-schema} via the raw-file API and uploads it to the
 *       {@code zk_backup_0/configs/<core>/managed-schema.xml} key the reader expects.</li>
 * </ul>
 *
 * <p>Per-version S3 module wiring differs: Solr 8 ships the S3 repository under
 * {@code /opt/solr/contrib/s3-repository/lib} (the main jar lives in {@code dist/} and is
 * copied in); Solr 9 ships it as a first-class module enabled via {@code SOLR_MODULES}.
 *
 * <p>Why this test exists: prior unit-style tests for the S3 marker helpers
 * (TestCreateSnapshotSolrS3) and the file-system-backed MetadataMigration
 * (SolrMetadataMigrationTest) together still missed a layout mismatch in the
 * reader-side discovery logic, because neither of them ran MetadataMigration
 * against real S3 data produced by CreateSnapshot. This test closes that gap
 * by wiring the real producer to the real consumer over LocalStack — for every
 * version/mode combination.
 *
 * <p>Layout exercised (Solr's S3BackupRepository backup format; the reader derives
 * mappings from the schema under each collection's latest {@code zk_backup_N}):
 * <pre>
 *   s3://bucket/[subpath/]&lt;snapshotName&gt;/
 *       &lt;collection&gt;/
 *           zk_backup_N/configs/&lt;cfg&gt;/managed-schema.xml   &lt;-- schema (read for mappings)
 *           shard_backup_metadata/ , index/ , ...            &lt;-- cloud only
 * </pre>
 *
 * <p>Tagged {@code isolatedTest} because each case spins up multiple containers
 * (LocalStack, Solr, OpenSearch).
 */
@Slf4j
@Tag("isolatedTest")
@Testcontainers
class SolrS3MetadataMigrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BUCKET_NAME = "solr-meta-s3-test";
    private static final String REGION = "us-east-1";
    private static final String LOCALSTACK_ALIAS = "localstack";
    private static final String COLLECTION = "s3_meta_coll";

    /** Which Solr deployment mode a test run targets. */
    enum Mode {
        CLOUD,
        STANDALONE
    }

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

    @BeforeAll
    static void createBucket() throws Exception {
        LOCAL_STACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_NAME);
        log.info("Created S3 bucket '{}' in LocalStack", BUCKET_NAME);
    }

    @TempDir
    File tempDir;

    static Stream<Arguments> versionsAndModes() {
        return Stream.of(
            // 8.10.1 is the earliest S3-capable Solr (S3BackupRepository / SIP-12 landed in 8.10).
            Arguments.of("8.10.1", Mode.CLOUD),
            Arguments.of("8.10.1", Mode.STANDALONE),
            Arguments.of("8.11.4", Mode.CLOUD),
            Arguments.of("8.11.4", Mode.STANDALONE),
            Arguments.of("9.8.1", Mode.CLOUD),
            Arguments.of("9.8.1", Mode.STANDALONE)
        );
    }

    /**
     * Full pipeline: Solr → CreateSnapshot → S3 (LocalStack) →
     * MetadataMigration (S3 branch) → OpenSearch, for each version/mode.
     */
    @ParameterizedTest(name = "Solr {0} {1} → S3 → MetadataMigration → OpenSearch")
    @MethodSource("versionsAndModes")
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void solrS3BackupProducesCorrectOpenSearchMappings(String solrVersion, Mode mode) throws Exception {
        try (
            var solr = startSolr(solrVersion, mode);
            var target = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)
        ) {
            CompletableFuture.runAsync(target::start).join();
            var targetOps = new ClusterOperations(target);

            var solrUrl = "http://" + solr.getHost() + ":" + solr.getMappedPort(8983);
            createCollectionWithSchema(solr, mode);
            indexOneDoc(solr);

            // Unique S3 prefix per case so cases never collide in the shared bucket.
            String tag = solrVersion.replace('.', '_') + "_" + mode.name().toLowerCase();
            String snapshotName = "meta_s3_snap_" + tag;
            String subpath = "v-" + tag;
            String s3RepoUri = "s3://" + BUCKET_NAME + "/" + subpath;

            // 1. CreateSnapshot → writes Solr's S3 backup layout (and, for standalone, exports the
            //    schema into zk_backup_0/configs/<core>/) to LocalStack.
            var createArgs = new CreateSnapshot.Args();
            createArgs.sourceArgs.host = solrUrl;
            createArgs.sourceArgs.insecure = true;
            createArgs.sourceType = "solr";
            createArgs.snapshotName = snapshotName;
            createArgs.snapshotRepoName = "s3";
            createArgs.s3RepoUri = s3RepoUri;
            createArgs.s3Region = REGION;
            createArgs.s3Endpoint = localStackEndpoint();
            createArgs.solrCollections = List.of(COLLECTION);
            createArgs.noWait = false;

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            new CreateSnapshot(createArgs, snapshotContext.createSnapshotCreateContext()).run();
            log.info("[{} {}] CreateSnapshot finished — S3 at {}/{}", solrVersion, mode, s3RepoUri, snapshotName);

            // 2. Run MetadataMigration exercising the S3 branch in ClusterReaderExtractor.
            var s3LocalDir = tempDir.toPath().resolve("s3-download-" + tag);
            Files.createDirectories(s3LocalDir);

            var metaArgs = new MigrateOrEvaluateArgs();
            metaArgs.sourceVersion = Version.fromString("SOLR " + solrVersion);
            metaArgs.snapshotName = snapshotName;
            metaArgs.s3RepoUri = s3RepoUri;
            metaArgs.s3Region = REGION;
            metaArgs.s3Endpoint = localStackEndpoint();
            metaArgs.s3LocalDirPath = s3LocalDir.toString();
            metaArgs.targetArgs.host = target.getUrl();

            var metadataContext = MetadataMigrationTestContext.factory().noOtelTracking();
            var result = new MetadataMigration().migrate(metaArgs).execute(metadataContext);

            log.atInfo().setMessage("[{} {}] Migration result: {}")
                .addArgument(solrVersion).addArgument(mode).addArgument(result.asCliOutput()).log();
            assertThat("Migration must succeed for Solr " + solrVersion + " " + mode,
                result.getExitCode(), equalTo(0));

            // 3. Verify target index mappings — full correctness, not just "no crash".
            var res = targetOps.get("/" + COLLECTION + "/_mapping");
            assertThat("Target mapping endpoint should return 200", res.getKey(), equalTo(200));

            var mappings = MAPPER.readTree(res.getValue());
            var properties = mappings.path(COLLECTION).path("mappings").path("properties");

            assertThat("title → keyword", properties.path("title").path("type").asText(), equalTo("keyword"));
            assertThat("count → integer", properties.path("count").path("type").asText(), equalTo("integer"));
            assertThat("created → date", properties.path("created").path("type").asText(), equalTo("date"));
            assertThat("description → text", properties.path("description").path("type").asText(), equalTo("text"));
            assertThat("active → boolean", properties.path("active").path("type").asText(), equalTo("boolean"));
        }
    }

    /**
     * Incremental cloud backup: take two successive backups to the same S3 location so Solr
     * accumulates {@code zk_backup_0} and {@code zk_backup_1}. Verifies the second revision is
     * present in S3, then asserts the migration still produces correct mappings — proving the
     * reader selects the latest {@code zk_backup_N} (highest N), not always {@code zk_backup_0}.
     * Uses 8.11.4 (incremental backups are the default since Solr 8.9).
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void incrementalCloudBackupReadsLatestZkBackupRevision() throws Exception {
        String version = "8.11.4";
        try (
            var solr = startSolr(version, Mode.CLOUD);
            var target = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)
        ) {
            CompletableFuture.runAsync(target::start).join();
            var targetOps = new ClusterOperations(target);

            var solrUrl = "http://" + solr.getHost() + ":" + solr.getMappedPort(8983);
            createCollectionWithSchema(solr, Mode.CLOUD);
            indexOneDoc(solr);

            String snapshotName = "meta_s3_snap_incremental";
            String subpath = "v-incremental";
            String s3RepoUri = "s3://" + BUCKET_NAME + "/" + subpath;

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();

            // First backup via the production CreateSnapshot path → zk_backup_0.
            var createArgs = new CreateSnapshot.Args();
            createArgs.sourceArgs.host = solrUrl;
            createArgs.sourceArgs.insecure = true;
            createArgs.sourceType = "solr";
            createArgs.snapshotName = snapshotName;
            createArgs.snapshotRepoName = "s3";
            createArgs.s3RepoUri = s3RepoUri;
            createArgs.s3Region = REGION;
            createArgs.s3Endpoint = localStackEndpoint();
            createArgs.solrCollections = List.of(COLLECTION);
            createArgs.noWait = false;
            new CreateSnapshot(createArgs, snapshotContext.createSnapshotCreateContext()).run();
            log.info("Incremental backup #0 finished (zk_backup_0)");

            // Second backup to the SAME Solr location/name → Solr adds zk_backup_1. Issued directly
            // (not via CreateSnapshot) with a fresh async id and an extra indexed doc, because Solr
            // caches a completed async id and would otherwise treat a repeated request as a no-op.
            indexDoc(solr, "2");
            String location = "/" + subpath + "/" + snapshotName; // matches buildPerCollectionLocation for S3
            solr.execInContainer("curl", "-sf",
                "http://localhost:8983/solr/admin/collections?action=BACKUP"
                    + "&name=" + COLLECTION
                    + "&collection=" + COLLECTION
                    + "&location=" + location
                    + "&repository=s3"
                    + "&async=incremental_round2"
                    + "&wt=json");
            waitForAsync(solr, "incremental_round2", 60);
            log.info("Incremental backup #1 finished (zk_backup_1)");

            // Confirm Solr actually produced a second revision (zk_backup_1) in S3.
            var listing = LOCAL_STACK.execInContainer(
                "awslocal", "s3", "ls",
                "s3://" + BUCKET_NAME + "/" + subpath + "/" + snapshotName + "/" + COLLECTION + "/",
                "--recursive");
            var s3Tree = listing.getStdout();
            log.atInfo().setMessage("Incremental S3 tree:\n{}").addArgument(s3Tree).log();
            assertThat("Second backup revision zk_backup_1 should exist in S3",
                s3Tree.contains("zk_backup_1"), equalTo(true));

            // Migration must read the LATEST revision and still produce correct mappings.
            var s3LocalDir = tempDir.toPath().resolve("s3-download-incremental");
            Files.createDirectories(s3LocalDir);

            var metaArgs = new MigrateOrEvaluateArgs();
            metaArgs.sourceVersion = Version.fromString("SOLR " + version);
            metaArgs.snapshotName = snapshotName;
            metaArgs.s3RepoUri = s3RepoUri;
            metaArgs.s3Region = REGION;
            metaArgs.s3Endpoint = localStackEndpoint();
            metaArgs.s3LocalDirPath = s3LocalDir.toString();
            metaArgs.targetArgs.host = target.getUrl();

            var metadataContext = MetadataMigrationTestContext.factory().noOtelTracking();
            var result = new MetadataMigration().migrate(metaArgs).execute(metadataContext);
            log.atInfo().setMessage("Incremental migration result: {}").addArgument(result.asCliOutput()).log();
            assertThat("Migration must succeed reading latest zk_backup revision",
                result.getExitCode(), equalTo(0));

            var res = targetOps.get("/" + COLLECTION + "/_mapping");
            assertThat("Target mapping endpoint should return 200", res.getKey(), equalTo(200));
            var properties = MAPPER.readTree(res.getValue())
                .path(COLLECTION).path("mappings").path("properties");
            assertThat("title → keyword", properties.path("title").path("type").asText(), equalTo("keyword"));
            assertThat("count → integer", properties.path("count").path("type").asText(), equalTo("integer"));
            assertThat("created → date", properties.path("created").path("type").asText(), equalTo("date"));
            assertThat("description → text", properties.path("description").path("type").asText(), equalTo("text"));
            assertThat("active → boolean", properties.path("active").path("type").asText(), equalTo("boolean"));
        }
    }

    // ---- container setup ----

    /**
     * Start a Solr container for the given version and mode, with the S3 backup repository
     * wired to LocalStack. Handles the per-version module layout:
     * <ul>
     *   <li>Solr 8: copy the main {@code solr-s3-repository} jar from {@code dist/} into the
     *       contrib lib dir referenced by {@code solr.xml}'s {@code sharedLib}.</li>
     *   <li>Solr 9: enable the {@code s3-repository} module via {@code SOLR_MODULES} and point
     *       {@code sharedLib} at the module lib dir.</li>
     * </ul>
     * Cloud mode starts SolrCloud ({@code -c}); standalone precreates the core and starts a
     * single node. Both run as root with {@code -force} (testcontainers default user).
     */
    @SuppressWarnings("resource")
    private static GenericContainer<?> startSolr(String version, Mode mode) {
        boolean solr9 = version.startsWith("9");
        String sharedLib = solr9
            ? "/opt/solr/modules/s3-repository/lib"
            : "/opt/solr/contrib/s3-repository/lib";

        var solrOpts = "-DS3_BUCKET_NAME=" + BUCKET_NAME
            + " -DS3_REGION=" + REGION
            + " -DS3_ENDPOINT=http://" + LOCALSTACK_ALIAS + ":4566"
            + " -DsharedLib=" + sharedLib;

        // Solr 8 needs the main s3-repository jar copied into the contrib lib; Solr 9's module
        // lib already contains everything, so its copy step is a harmless no-op (|| true).
        String enableS3 = solr9
            ? "true"
            : "cp /opt/solr/dist/solr-s3-repository-*.jar " + sharedLib + "/";

        String startCmd = mode == Mode.CLOUD
            ? "exec solr-foreground -c -force"
            : "precreate-core " + COLLECTION + " && exec solr-foreground -force";

        var container = new GenericContainer<>(DockerImageName.parse("solr:" + version))
            .withNetwork(NETWORK)
            .withExposedPorts(8983)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("solr-s3-backup.xml"),
                "/var/solr/data/solr.xml")
            .withEnv("AWS_ACCESS_KEY_ID", "test")
            .withEnv("AWS_SECRET_ACCESS_KEY", "test")
            .withEnv("SOLR_OPTS", solrOpts)
            .withEnv("SOLR_SECURITY_MANAGER_ENABLED", "false")
            .withCreateContainerCmdModifier(cmd -> cmd.withUser("root"))
            .withCommand("bash", "-c",
                "init-var-solr && " + enableS3 + " && " + startCmd);

        if (solr9) {
            container.withEnv("SOLR_MODULES", "s3-repository");
        }

        var wait = mode == Mode.CLOUD
            ? Wait.forHttp("/solr/admin/collections?action=LIST&wt=json")
                .forPort(8983).forStatusCode(200)
            : Wait.forHttp("/solr/admin/cores?action=STATUS&indexInfo=false&wt=json")
                .forPort(8983).forStatusCode(200)
                .forResponsePredicate(body -> body.contains("\"" + COLLECTION + "\""));
        container.waitingFor(wait.withStartupTimeout(Duration.ofMinutes(3)));

        log.info("Starting Solr {} ({})", version, mode);
        container.start();
        return container;
    }

    // ---- Solr data setup ----

    private static String localStackEndpoint() {
        return LOCAL_STACK.getEndpoint().toString();
    }

    /** Create a SolrCloud collection (standalone core is precreated at startup), then add fields. */
    private void createCollectionWithSchema(GenericContainer<?> solr, Mode mode) throws Exception {
        if (mode == Mode.CLOUD) {
            solr.execInContainer("curl", "-sf",
                "http://localhost:8983/solr/admin/collections?action=CREATE"
                    + "&name=" + COLLECTION
                    + "&numShards=1"
                    + "&replicationFactor=1"
                    + "&maxShardsPerNode=1"
                    + "&wt=json");
        }
        addField(solr, "title", "string");
        addField(solr, "count", "pint");
        addField(solr, "created", "pdate");
        addField(solr, "description", "text_general");
        addField(solr, "active", "boolean");
        log.info("[{}] Created '{}' with schema fields", mode, COLLECTION);
    }

    private void addField(GenericContainer<?> solr, String fieldName, String type) throws Exception {
        solr.execInContainer("curl", "-sf",
            "http://localhost:8983/solr/" + COLLECTION + "/schema",
            "-H", "Content-Type: application/json",
            "-d", "{\"add-field\":{\"name\":\"" + fieldName + "\",\"type\":\"" + type + "\",\"stored\":true}}");
    }

    private void indexOneDoc(GenericContainer<?> solr) throws Exception {
        indexDoc(solr, "1");
        log.info("Indexed 1 doc into '{}'", COLLECTION);
    }

    /** Index (and commit) a single doc with the given id, so the index changes between backups. */
    private void indexDoc(GenericContainer<?> solr, String id) throws Exception {
        solr.execInContainer("curl", "-sf",
            "http://localhost:8983/solr/" + COLLECTION + "/update?commit=true",
            "-H", "Content-Type: application/json",
            "-d", "[{\"id\":\"" + id + "\",\"title\":\"test\",\"count\":42,"
                + "\"created\":\"2024-01-01T00:00:00Z\","
                + "\"description\":\"hello world\",\"active\":true}]");
    }

    /** Poll a Collections API async request id until it completes (or fail after maxWaitSeconds). */
    private void waitForAsync(GenericContainer<?> solr, String asyncId, int maxWaitSeconds) throws Exception {
        for (int i = 0; i < maxWaitSeconds; i++) {
            var resp = solr.execInContainer("curl", "-sf",
                "http://localhost:8983/solr/admin/collections?action=REQUESTSTATUS&requestid="
                    + asyncId + "&wt=json").getStdout();
            if (resp.contains("\"state\":\"completed\"")) {
                return;
            }
            if (resp.contains("\"state\":\"failed\"")) {
                throw new IllegalStateException("Async backup '" + asyncId + "' failed: " + resp);
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Async backup '" + asyncId + "' did not complete in "
            + maxWaitSeconds + "s");
    }
}
