package org.opensearch.migrations;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.opensearch.migrations.bulkload.solr.framework.SolrClusterContainer;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.testutils.CloseableLogSetup;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Unit and integration tests for the unified CreateSnapshot mode flag behavior.
 * Covers:
 * <ol>
 *   <li>SnapshotMode enum parsing and defaults</li>
 *   <li>CreateSnapshot accepts and respects the mode flag</li>
 *   <li>Config file check-and-upload runs in both CREATE and IMPORT modes</li>
 *   <li>Existing configs in S3 are not re-uploaded</li>
 *   <li>IMPORT mode does not perform backup operations</li>
 *   <li>Standalone Solr clusters use the unified CreateSnapshot path</li>
 * </ol>
 */
@Slf4j
@Testcontainers
@Tag("isolatedTest")
public class TestCreateSnapshotModeFlag {

    private static final String BUCKET_NAME = "mode-flag-test";
    private static final String REGION = "us-east-1";

    static {
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
        .withNetworkAliases("localstack");

    @Container
    static final SolrClusterContainer STANDALONE_SOLR = new SolrClusterContainer(SolrClusterContainer.SOLR_8);

    @Container
    static final SolrClusterContainer CLOUD_SOLR = SolrClusterContainer.cloud(SolrClusterContainer.SOLR_8);

    static {
        // Create the S3 bucket on first access
        try {
            // Deferred to @BeforeAll-style via lazy init
        } catch (Exception ignored) {}
    }

    private static boolean bucketCreated = false;

    private void ensureBucket() throws Exception {
        if (!bucketCreated) {
            LOCAL_STACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_NAME);
            bucketCreated = true;
        }
    }

    private S3Client testS3Client() {
        return S3Client.builder()
            .region(Region.of(REGION))
            .endpointOverride(LOCAL_STACK.getEndpoint())
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")))
            .forcePathStyle(true)
            .build();
    }

    private boolean s3ObjectExists(S3Client client, String key) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(BUCKET_NAME).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    private String s3ObjectContent(S3Client client, String key) throws Exception {
        try (var stream = client.getObject(GetObjectRequest.builder()
                .bucket(BUCKET_NAME).key(key).build())) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ── SnapshotMode enum tests ──────────────────────────────────────────────

    @Test
    void snapshotMode_fromString_defaultsToCreate() {
        Assertions.assertEquals(SnapshotMode.CREATE, SnapshotMode.fromString(null));
        Assertions.assertEquals(SnapshotMode.CREATE, SnapshotMode.fromString(""));
    }

    @Test
    void snapshotMode_fromString_parsesCreate() {
        Assertions.assertEquals(SnapshotMode.CREATE, SnapshotMode.fromString("create"));
        Assertions.assertEquals(SnapshotMode.CREATE, SnapshotMode.fromString("CREATE"));
        Assertions.assertEquals(SnapshotMode.CREATE, SnapshotMode.fromString("Create"));
    }

    @Test
    void snapshotMode_fromString_parsesImport() {
        Assertions.assertEquals(SnapshotMode.IMPORT, SnapshotMode.fromString("import"));
        Assertions.assertEquals(SnapshotMode.IMPORT, SnapshotMode.fromString("IMPORT"));
        Assertions.assertEquals(SnapshotMode.IMPORT, SnapshotMode.fromString("Import"));
    }

    @Test
    void snapshotMode_fromString_invalidThrows() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> SnapshotMode.fromString("invalid"));
    }

    // ── Args mode flag default ──────────────────────────────────────────────

    @Test
    void args_modeDefaultsToCreate() {
        var args = new CreateSnapshot.Args();
        Assertions.assertEquals("create", args.mode);
        Assertions.assertEquals(SnapshotMode.CREATE, CreateSnapshot.getSnapshotMode(args));
    }

    @Test
    void args_modeImport_resolves() {
        var args = new CreateSnapshot.Args();
        args.mode = "import";
        Assertions.assertEquals(SnapshotMode.IMPORT, CreateSnapshot.getSnapshotMode(args));
    }

    // ── Config file check runs in CREATE mode ─────────────────────────────────

    @Test
    void createMode_configFilesUploadedToS3WhenMissing() throws Exception {
        ensureBucket();
        String snapshotName = "cfg_create_test";
        String subpath = "cfg-create";

        // Create a collection in cloud Solr
        CLOUD_SOLR.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=CREATE"
                + "&name=cfgcoll&numShards=1&replicationFactor=1&wt=json");

        var args = new CreateSnapshot.Args();
        args.sourceArgs.host = cloudSolrUrl();
        args.sourceArgs.insecure = true;
        args.sourceType = "solr";
        args.snapshotName = snapshotName;
        args.snapshotRepoName = "test";
        args.s3RepoUri = "s3://" + BUCKET_NAME + "/" + subpath;
        args.s3Region = REGION;
        args.s3Endpoint = LOCAL_STACK.getEndpoint().toString();
        args.mode = "create";
        args.solrCollections = List.of("cfgcoll");
        args.noWait = false;

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());

        // Run may fail on actual backup (Solr not configured for S3 backup in this container),
        // but config file check runs BEFORE the backup step. Catch and verify config was uploaded.
        try {
            creator.run();
        } catch (Exception e) {
            log.info("Expected backup failure (Solr not S3-configured): {}", e.getMessage());
        }

        // Verify config file was uploaded to the expected S3 path
        String expectedKey = subpath + "/" + snapshotName + "/cfgcoll/zk_backup_0/configs/cfgcoll/managed-schema.xml";
        try (var s3 = testS3Client()) {
            Assertions.assertTrue(s3ObjectExists(s3, expectedKey),
                "Config file should be uploaded to S3 in CREATE mode at: " + expectedKey);
            String content = s3ObjectContent(s3, expectedKey);
            Assertions.assertFalse(content.isBlank(), "Config file content should not be empty");
            // Schema files contain XML with field definitions
            Assertions.assertTrue(content.contains("<") && content.contains(">"),
                "Config file should be XML content; got: " + content.substring(0, Math.min(200, content.length())));
        }

        // Clean up collection
        CLOUD_SOLR.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=DELETE&name=cfgcoll&wt=json");
    }

    // ── Config file check runs in IMPORT mode ─────────────────────────────────

    @Test
    void importMode_configFilesUploadedToS3WhenMissing() throws Exception {
        ensureBucket();
        String snapshotName = "cfg_import_test";
        String subpath = "cfg-import";

        // Create a collection in cloud Solr
        CLOUD_SOLR.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=CREATE"
                + "&name=importcoll&numShards=1&replicationFactor=1&wt=json");

        var args = new CreateSnapshot.Args();
        args.sourceArgs.host = cloudSolrUrl();
        args.sourceArgs.insecure = true;
        args.sourceType = "solr";
        args.snapshotName = snapshotName;
        args.snapshotRepoName = "test";
        args.s3RepoUri = "s3://" + BUCKET_NAME + "/" + subpath;
        args.s3Region = REGION;
        args.s3Endpoint = LOCAL_STACK.getEndpoint().toString();
        args.mode = "import";  // IMPORT mode
        args.solrCollections = List.of("importcoll");
        args.noWait = false;

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
        creator.run();  // IMPORT mode should succeed (no backup, just config check + validation)

        // Verify config file was uploaded in IMPORT mode too
        String expectedKey = subpath + "/" + snapshotName + "/importcoll/zk_backup_0/configs/importcoll/managed-schema.xml";
        try (var s3 = testS3Client()) {
            Assertions.assertTrue(s3ObjectExists(s3, expectedKey),
                "Config file should be uploaded to S3 in IMPORT mode at: " + expectedKey);
        }

        // Clean up
        CLOUD_SOLR.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=DELETE&name=importcoll&wt=json");
    }

    // ── Existing configs are NOT re-uploaded ──────────────────────────────────

    @Test
    void importMode_existingConfigNotReUploaded() throws Exception {
        ensureBucket();
        String snapshotName = "cfg_existing_test";
        String subpath = "cfg-existing";
        String collection = "existcoll";

        // Create a collection
        CLOUD_SOLR.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=CREATE"
                + "&name=" + collection + "&numShards=1&replicationFactor=1&wt=json");

        // Pre-upload a sentinel config file to S3
        String configKey = subpath + "/" + snapshotName + "/" + collection
            + "/zk_backup_0/configs/" + collection + "/managed-schema.xml";
        String sentinelContent = "<schema>SENTINEL_DO_NOT_OVERWRITE</schema>";
        try (var s3 = testS3Client()) {
            s3.putObject(
                PutObjectRequest.builder().bucket(BUCKET_NAME).key(configKey).build(),
                RequestBody.fromString(sentinelContent, StandardCharsets.UTF_8));
        }

        // Run in IMPORT mode
        var args = new CreateSnapshot.Args();
        args.sourceArgs.host = cloudSolrUrl();
        args.sourceArgs.insecure = true;
        args.sourceType = "solr";
        args.snapshotName = snapshotName;
        args.snapshotRepoName = "test";
        args.s3RepoUri = "s3://" + BUCKET_NAME + "/" + subpath;
        args.s3Region = REGION;
        args.s3Endpoint = LOCAL_STACK.getEndpoint().toString();
        args.mode = "import";
        args.solrCollections = List.of(collection);
        args.noWait = false;

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext()).run();

        // Verify sentinel content was preserved (not overwritten)
        try (var s3 = testS3Client()) {
            String actual = s3ObjectContent(s3, configKey);
            Assertions.assertEquals(sentinelContent, actual,
                "Pre-existing config in S3 must NOT be re-uploaded; content should be preserved");
        }

        // Clean up
        CLOUD_SOLR.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=DELETE&name=" + collection + "&wt=json");
    }

    // ── IMPORT mode does NOT perform backup ───────────────────────────────────

    @Test
    void importMode_noBackupPerformed() throws Exception {
        ensureBucket();
        String snapshotName = "import_no_backup";
        String subpath = "import-nobkp";

        CLOUD_SOLR.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=CREATE"
                + "&name=nobkpcoll&numShards=1&replicationFactor=1&wt=json");

        var args = new CreateSnapshot.Args();
        args.sourceArgs.host = cloudSolrUrl();
        args.sourceArgs.insecure = true;
        args.sourceType = "solr";
        args.snapshotName = snapshotName;
        args.snapshotRepoName = "test";
        args.s3RepoUri = "s3://" + BUCKET_NAME + "/" + subpath;
        args.s3Region = REGION;
        args.s3Endpoint = LOCAL_STACK.getEndpoint().toString();
        args.mode = "import";
        args.solrCollections = List.of("nobkpcoll");
        args.noWait = false;

        try (var logCapture = new CloseableLogSetup(SolrBackupStrategy.class.getName())) {
            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext()).run();

            // Verify IMPORT mode logged, not CREATE
            boolean importLogged = logCapture.getLogEvents().stream()
                .anyMatch(m -> m.contains("IMPORT mode"));
            Assertions.assertTrue(importLogged, "Should log IMPORT mode execution");

            // No backup-related log entries
            boolean backupLogged = logCapture.getLogEvents().stream()
                .anyMatch(m -> m.contains("using replication API backup")
                    || m.contains("using Collections API backup"));
            Assertions.assertFalse(backupLogged, "IMPORT mode should NOT trigger backup operations");
        }

        // Verify no backup data was written (only config file + maybe markers)
        try (var s3 = testS3Client()) {
            var keys = testS3Client().listObjectsV2(ListObjectsV2Request.builder()
                .bucket(BUCKET_NAME).prefix(subpath + "/" + snapshotName + "/nobkpcoll/").build())
                .contents().stream().map(o -> o.key()).toList();
            // Only config-related keys should exist, no index/ or shard_backup_metadata/
            boolean hasBackupData = keys.stream()
                .anyMatch(k -> k.contains("/index/") || k.contains("/shard_backup_metadata/"));
            Assertions.assertFalse(hasBackupData,
                "IMPORT mode should NOT produce backup index/shard data; keys=" + keys);
        }

        CLOUD_SOLR.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=DELETE&name=nobkpcoll&wt=json");
    }

    // ── Standalone Solr uses unified path ─────────────────────────────────────

    @Test
    void standalone_usesUnifiedCreateSnapshotPath_createMode() throws Exception {
        var solrUrl = STANDALONE_SOLR.getSolrUrl();
        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();

        var args = new CreateSnapshot.Args();
        args.sourceArgs.host = solrUrl;
        args.sourceArgs.insecure = true;
        args.sourceType = "solr";
        args.snapshotName = "standalone_unified_test";
        args.snapshotRepoName = "test";
        args.fileSystemRepoPath = "/var/solr/data";
        args.mode = "create";
        args.noWait = false;

        try (var logCapture = new CloseableLogSetup(SolrBackupStrategy.class.getName())) {
            var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
            creator.run();

            // Verify it went through the unified SolrBackupStrategy (not a separate standalone path)
            boolean standaloneDetected = logCapture.getLogEvents().stream()
                .anyMatch(m -> m.contains("topology=standalone"));
            Assertions.assertTrue(standaloneDetected,
                "Standalone Solr should be detected and logged via the unified SolrBackupStrategy");

            boolean createMode = logCapture.getLogEvents().stream()
                .anyMatch(m -> m.contains("CREATE mode"));
            Assertions.assertTrue(createMode,
                "Should execute CREATE mode for standalone");
        }
    }

    @Test
    void standalone_usesUnifiedCreateSnapshotPath_importMode() throws Exception {
        var solrUrl = STANDALONE_SOLR.getSolrUrl();
        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();

        var args = new CreateSnapshot.Args();
        args.sourceArgs.host = solrUrl;
        args.sourceArgs.insecure = true;
        args.sourceType = "solr";
        args.snapshotName = "standalone_import_test";
        args.snapshotRepoName = "test";
        args.fileSystemRepoPath = "/var/solr/data";
        args.mode = "import";
        args.noWait = false;

        try (var logCapture = new CloseableLogSetup(SolrBackupStrategy.class.getName())) {
            var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
            creator.run();

            // Standalone in IMPORT mode should also go through unified path
            boolean standaloneDetected = logCapture.getLogEvents().stream()
                .anyMatch(m -> m.contains("topology=standalone"));
            Assertions.assertTrue(standaloneDetected,
                "Standalone Solr should be detected in IMPORT mode");

            boolean importMode = logCapture.getLogEvents().stream()
                .anyMatch(m -> m.contains("IMPORT mode"));
            Assertions.assertTrue(importMode,
                "Should execute IMPORT mode for standalone");

            // Should NOT trigger any backup
            boolean backupTriggered = logCapture.getLogEvents().stream()
                .anyMatch(m -> m.contains("replication API backup"));
            Assertions.assertFalse(backupTriggered,
                "IMPORT mode on standalone should NOT trigger replication backup");
        }
    }

    // ── Mode branching produces correct behavior ──────────────────────────────

    @Test
    void modeBranching_createModeTriggersBackup() throws Exception {
        var solrUrl = STANDALONE_SOLR.getSolrUrl();
        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();

        var args = new CreateSnapshot.Args();
        args.sourceArgs.host = solrUrl;
        args.sourceArgs.insecure = true;
        args.sourceType = "solr";
        args.snapshotName = "branch_create_test";
        args.snapshotRepoName = "test";
        args.fileSystemRepoPath = "/var/solr/data";
        args.mode = "create";
        args.noWait = false;

        try (var logCapture = new CloseableLogSetup(SolrBackupStrategy.class.getName())) {
            new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext()).run();

            boolean backupExecuted = logCapture.getLogEvents().stream()
                .anyMatch(m -> m.contains("CREATE mode: backing up"));
            Assertions.assertTrue(backupExecuted,
                "CREATE mode should trigger backup execution");
        }
    }

    @Test
    void modeBranching_importModeTriggersValidation() throws Exception {
        ensureBucket();

        CLOUD_SOLR.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=CREATE"
                + "&name=branchcoll&numShards=1&replicationFactor=1&wt=json");

        var args = new CreateSnapshot.Args();
        args.sourceArgs.host = cloudSolrUrl();
        args.sourceArgs.insecure = true;
        args.sourceType = "solr";
        args.snapshotName = "branch_import_test";
        args.snapshotRepoName = "test";
        args.s3RepoUri = "s3://" + BUCKET_NAME + "/branch-import";
        args.s3Region = REGION;
        args.s3Endpoint = LOCAL_STACK.getEndpoint().toString();
        args.mode = "import";
        args.solrCollections = List.of("branchcoll");
        args.noWait = false;

        try (var logCapture = new CloseableLogSetup(SolrBackupStrategy.class.getName())) {
            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext()).run();

            boolean validationExecuted = logCapture.getLogEvents().stream()
                .anyMatch(m -> m.contains("IMPORT mode: verifying snapshot location"));
            Assertions.assertTrue(validationExecuted,
                "IMPORT mode should trigger snapshot location validation");

            boolean importComplete = logCapture.getLogEvents().stream()
                .anyMatch(m -> m.contains("IMPORT mode complete"));
            Assertions.assertTrue(importComplete,
                "IMPORT mode should complete with confirmation message");
        }

        CLOUD_SOLR.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=DELETE&name=branchcoll&wt=json");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String cloudSolrUrl() {
        return "http://" + CLOUD_SOLR.getHost() + ":" + CLOUD_SOLR.getMappedPort(8983);
    }
}
