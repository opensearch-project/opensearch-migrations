package org.opensearch.migrations;

import java.time.Duration;
import java.util.List;

import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.solr.SolrHttpClient;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * End-to-end testcontainer-based tests for the S3 directory marker logic and the
 * full Solr-to-S3 backup code path exercised by {@link SolrBackupStrategy} /
 * {@link CreateSnapshot} against real SolrCloud + real S3 (LocalStack).
 *
 * These tests run under the {@code isolatedTest} Gradle task (tag: isolatedTest).
 */
@Slf4j
@Testcontainers
@Tag("isolatedTest")
public class TestCreateSnapshotSolrS3 {

    private static final String BUCKET_NAME = "solr-backup-test";
    private static final String REGION = "us-east-1";
    private static final String LOCALSTACK_ALIAS = "localstack";

    static {
        // LocalStack accepts any non-empty credentials, but the production S3Client is built with
        // DefaultCredentialsProvider (the right choice for real customers: IAM role / env / profile).
        // CI runners have no ambient AWS creds, so seed sysprops before the SDK's credential chain
        // is first consulted. Must happen in a static block (not @BeforeAll) so it runs before any
        // class-level container initialization that may indirectly instantiate an S3Client.
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
            if (name.isEmpty()) continue;
            SOLR_CLOUD.execInContainer("curl", "-sf",
                "http://localhost:8983/solr/admin/collections?action=DELETE&name=" + name + "&wt=json");
            log.info("Deleted SolrCloud collection '{}'", name);
        }
    }

    private static String solrUrl() {
        return "http://" + SOLR_CLOUD.getHost() + ":" + SOLR_CLOUD.getMappedPort(8983);
    }

    private static String localStackEndpoint() {
        return LOCAL_STACK.getEndpoint().toString();
    }

    private static SolrHttpClient solrHttpClient() {
        return new SolrHttpClient(connectionContext());
    }

    private static ConnectionContext connectionContext() {
        var args = new ConnectionContext.SourceArgs();
        args.host = solrUrl();
        args.insecure = true;
        return args.toConnectionContext();
    }

    private static S3Client testS3Client() {
        return S3Client.builder()
            .region(Region.of(REGION))
            .endpointOverride(LOCAL_STACK.getEndpoint())
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")))
            .forcePathStyle(true)
            .build();
    }

    private CreateSnapshot.Args makeArgs(String s3RepoUri, String snapshotName) {
        var args = new CreateSnapshot.Args();
        args.sourceArgs.host = solrUrl();
        args.sourceArgs.insecure = true;
        args.sourceType = "solr";
        args.snapshotName = snapshotName;
        args.snapshotRepoName = "s3";
        args.s3RepoUri = s3RepoUri;
        args.s3Region = REGION;
        args.s3Endpoint = localStackEndpoint();
        args.noWait = false;
        return args;
    }

    private void createCollection(String name, int numShards) throws Exception {
        int maxShards = Math.max(numShards, 1);
        SOLR_CLOUD.execInContainer("curl", "-sf",
            "http://localhost:8983/solr/admin/collections?action=CREATE"
                + "&name=" + name
                + "&numShards=" + numShards
                + "&replicationFactor=1"
                + "&maxShardsPerNode=" + maxShards
                + "&wt=json");
        log.info("Created SolrCloud collection '{}' with {} shards", name, numShards);
    }

    private void indexDocs(String collection, int count) throws Exception {
        var sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"id\":\"doc-").append(i)
              .append("\",\"title\":\"Test document ").append(i).append("\"}");
        }
        sb.append("]");
        SOLR_CLOUD.execInContainer("curl", "-sf",
            "http://localhost:8983/solr/" + collection + "/update?commit=true",
            "-H", "Content-Type: application/json",
            "-d", sb.toString());
        log.info("Indexed {} documents into collection '{}'", count, collection);
    }

    private boolean s3ObjectExists(S3Client client, String key) {
        try {
            client.headObject(HeadObjectRequest.builder()
                .bucket(BUCKET_NAME)
                .key(key)
                .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    private List<String> listS3Keys(S3Client client, String prefix) {
        ListObjectsV2Response response = client.listObjectsV2(ListObjectsV2Request.builder()
            .bucket(BUCKET_NAME)
            .prefix(prefix)
            .build());
        return response.contents().stream().map(o -> o.key()).toList();
    }

    // -- full end-to-end backup flow tests (Solr + LocalStack) --

    @Test
    void cloudBackupToBucketRoot_succeeds() throws Exception {
        String collName = "bucket_root_coll";
        createCollection(collName, 1);
        indexDocs(collName, 10);

        String snapshotName = "backup_root_test";
        var args = makeArgs("s3://" + BUCKET_NAME, snapshotName);
        args.solrCollections = List.of(collName);

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
        creator.run();

        // Verify backup files appear under <snapshotName>/<collection>/
        try (var s3 = testS3Client()) {
            var keys = listS3Keys(s3, snapshotName + "/");
            log.info("S3 keys under '{}/*/': {}", snapshotName, keys);
            Assertions.assertFalse(keys.isEmpty(),
                "Backup files should exist under " + snapshotName + "/*/ in S3");
            // Collection subdir must be a direct child: <snapshotName>/<collName>/...
            Assertions.assertTrue(
                keys.stream().anyMatch(k -> k.startsWith(snapshotName + "/" + collName + "/")),
                "Collection " + collName + " must be a direct child of snapshot root; keys=" + keys);
        }
    }

    @Test
    void cloudBackupToSubpath_createsMarkerAndBackupSucceeds() throws Exception {
        String collName = "subpath_coll";
        createCollection(collName, 1);
        indexDocs(collName, 15);

        String subpath = "migration-v1";
        String snapshotName = "backup_subpath_test";
        var args = makeArgs("s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
        args.solrCollections = List.of(collName);

        try (var s3 = testS3Client()) {
            Assertions.assertFalse(s3ObjectExists(s3, subpath + "/"),
                "Marker should not exist before backup");
        }

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
        creator.run();

        try (var s3 = testS3Client()) {
            Assertions.assertTrue(s3ObjectExists(s3, subpath + "/"),
                "Parent directory marker should exist at " + subpath + "/");
            Assertions.assertTrue(s3ObjectExists(s3, subpath + "/" + snapshotName + "/"),
                "Per-snapshot marker should exist at " + subpath + "/" + snapshotName + "/");
            // Backup files live under <subpath>/<snapshotName>/<collection>/
            var keys = listS3Keys(s3, subpath + "/" + snapshotName + "/");
            log.info("S3 keys under '{}/{}/*/': {}", subpath, snapshotName, keys);
            Assertions.assertFalse(keys.isEmpty(),
                "Backup files should exist under " + subpath + "/" + snapshotName + "/*/ in S3");
            Assertions.assertTrue(
                keys.stream().anyMatch(k -> k.startsWith(subpath + "/" + snapshotName + "/" + collName + "/")),
                "Collection " + collName + " must be a direct child of snapshot root; keys=" + keys);
        }
    }

    @Test
    void cloudBackupToNestedSubpath_createsMarkerAndBackupSucceeds() throws Exception {
        String collName = "nested_subpath_coll";
        createCollection(collName, 1);
        indexDocs(collName, 5);

        String subpath = "deep/nested/path";
        String snapshotName = "backup_nested_test";
        var args = makeArgs("s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
        args.solrCollections = List.of(collName);

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
        creator.run();

        try (var s3 = testS3Client()) {
            Assertions.assertTrue(s3ObjectExists(s3, subpath + "/"),
                "Parent directory marker should exist at " + subpath + "/");
            Assertions.assertTrue(s3ObjectExists(s3, subpath + "/" + snapshotName + "/"),
                "Per-snapshot marker should exist at " + subpath + "/" + snapshotName + "/");
            var keys = listS3Keys(s3, subpath + "/" + snapshotName + "/");
            log.info("S3 keys under '{}/{}/*/': {}", subpath, snapshotName, keys);
            Assertions.assertFalse(keys.isEmpty(),
                "Backup files should exist under nested subpath");
        }
    }

    @Test
    void cloudBackupToSubpath_multipleCollections_singleMarker() throws Exception {
        String coll1 = "multi_coll_1";
        String coll2 = "multi_coll_2";
        createCollection(coll1, 1);
        createCollection(coll2, 1);
        indexDocs(coll1, 8);
        indexDocs(coll2, 12);

        String subpath = "multi-coll-test";
        String snapshotName = "backup_multi_coll";
        var args = makeArgs("s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
        args.solrCollections = List.of(coll1, coll2);

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
        creator.run();

        try (var s3 = testS3Client()) {
            Assertions.assertTrue(s3ObjectExists(s3, subpath + "/"),
                "Single parent directory marker should exist for multiple collections");
            Assertions.assertTrue(s3ObjectExists(s3, subpath + "/" + snapshotName + "/"),
                "Per-snapshot marker should exist once regardless of collection count");
            // Each collection lives under <subpath>/<snapshotName>/<collection>/ — the layout
            // the downstream reader (RfsMigrateDocuments) walks via listTopLevelDirectories.
            var keys1 = listS3Keys(s3, subpath + "/" + snapshotName + "/" + coll1 + "/");
            var keys2 = listS3Keys(s3, subpath + "/" + snapshotName + "/" + coll2 + "/");
            log.info("S3 keys for {}: {}", coll1, keys1);
            log.info("S3 keys for {}: {}", coll2, keys2);
            Assertions.assertFalse(keys1.isEmpty(),
                "Backup files should exist for collection " + coll1);
            Assertions.assertFalse(keys2.isEmpty(),
                "Backup files should exist for collection " + coll2);
        }
    }

    @Test
    void cloudBackupToSubpath_multiShard_succeeds() throws Exception {
        String collName = "multishard_s3_coll";
        createCollection(collName, 2);
        indexDocs(collName, 20);

        String subpath = "multishard-test";
        String snapshotName = "backup_multishard";
        var args = makeArgs("s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
        args.solrCollections = List.of(collName);

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
        creator.run();

        try (var s3 = testS3Client()) {
            Assertions.assertTrue(s3ObjectExists(s3, subpath + "/"),
                "Parent directory marker should exist");
            Assertions.assertTrue(s3ObjectExists(s3, subpath + "/" + snapshotName + "/"),
                "Per-snapshot marker should exist");
            var keys = listS3Keys(s3, subpath + "/" + snapshotName + "/");
            log.info("S3 keys for multi-shard backup: {}", keys);
            Assertions.assertFalse(keys.isEmpty(),
                "Multi-shard backup files should exist in S3");
            var shardMetaKeys = keys.stream().filter(k -> k.contains("md_shard")).toList();
            log.info("Shard metadata keys: {}", shardMetaKeys);
        }
    }

    @Test
    void cloudBackupToSubpath_preExistingMarker_idempotentAndSucceeds() throws Exception {
        String collName = "preexist_marker_coll";
        createCollection(collName, 1);
        indexDocs(collName, 5);

        String subpath = "preexist-marker";
        String snapshotName = "backup_preexist";
        var args = makeArgs("s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
        args.solrCollections = List.of(collName);

        // Pre-create marker
        try (var s3 = testS3Client()) {
            s3.putObject(PutObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(subpath + "/")
                    .contentType("application/x-directory")
                    .build(),
                RequestBody.empty());
        }

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
        creator.run();

        try (var s3 = testS3Client()) {
            var keys = listS3Keys(s3, subpath + "/" + snapshotName + "/");
            Assertions.assertFalse(keys.isEmpty(),
                "Backup should succeed with pre-existing parent marker");
        }
    }

    @Test
    void cloudBackup_successiveSnapshots_isolatedInS3() throws Exception {
        String collName = "successive_coll";
        createCollection(collName, 1);
        indexDocs(collName, 3);

        String subpath = "successive-test";

        // First snapshot
        var args1 = makeArgs("s3://" + BUCKET_NAME + "/" + subpath, "snap_v1");
        args1.solrCollections = List.of(collName);
        var ctx1 = SnapshotTestContext.factory().noOtelTracking();
        new CreateSnapshot(args1, ctx1.createSnapshotCreateContext()).run();

        // Index more docs, then second snapshot
        indexDocs(collName, 5);
        var args2 = makeArgs("s3://" + BUCKET_NAME + "/" + subpath, "snap_v2");
        args2.solrCollections = List.of(collName);
        var ctx2 = SnapshotTestContext.factory().noOtelTracking();
        new CreateSnapshot(args2, ctx2.createSnapshotCreateContext()).run();

        try (var s3 = testS3Client()) {
            var keysV1 = listS3Keys(s3, subpath + "/snap_v1/" + collName + "/");
            var keysV2 = listS3Keys(s3, subpath + "/snap_v2/" + collName + "/");
            log.info("snap_v1 keys: {}", keysV1);
            log.info("snap_v2 keys: {}", keysV2);
            Assertions.assertFalse(keysV1.isEmpty(), "First snapshot should have data");
            Assertions.assertFalse(keysV2.isEmpty(), "Second snapshot should have data");
            // Both should be under the same parent subpath but different snapshot dirs
            Assertions.assertTrue(s3ObjectExists(s3, subpath + "/snap_v1/"),
                "snap_v1 marker should exist");
            Assertions.assertTrue(s3ObjectExists(s3, subpath + "/snap_v2/"),
                "snap_v2 marker should exist");
        }
    }

    @Test
    void cloudBackup_s3UriWithTrailingSlash_succeeds() throws Exception {
        String collName = "trailing_slash_coll";
        createCollection(collName, 1);
        indexDocs(collName, 3);

        String subpath = "trailing-slash-test";
        String snapshotName = "backup_trailing";
        // Note trailing slash in the URI — should be handled gracefully
        var args = makeArgs("s3://" + BUCKET_NAME + "/" + subpath + "/", snapshotName);
        args.solrCollections = List.of(collName);

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
        creator.run();

        try (var s3 = testS3Client()) {
            // The trailing slash should not cause double slashes or missing markers
            var keys = listS3Keys(s3, subpath + "/");
            log.info("Keys after trailing-slash URI backup: {}", keys);
            Assertions.assertFalse(keys.isEmpty(), "Backup should succeed with trailing-slash URI");
            // Should still find backup data under the expected path
            var backupKeys = listS3Keys(s3, subpath + "/" + snapshotName + "/" + collName + "/");
            Assertions.assertFalse(backupKeys.isEmpty(),
                "Backup data should exist at expected path despite trailing slash in URI");
        }
    }

    @Test
    void cloudBackupToSubpath_autoDiscoverCollections() throws Exception {
        String collName = "autodiscover_coll";
        createCollection(collName, 1);
        indexDocs(collName, 5);

        String subpath = "autodiscover-test";
        String snapshotName = "backup_autodiscover";
        var args = makeArgs("s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
        args.solrCollections = List.of();

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
        creator.run();

        try (var s3 = testS3Client()) {
            Assertions.assertTrue(s3ObjectExists(s3, subpath + "/"),
                "Parent directory marker should exist after auto-discovery backup");
            Assertions.assertTrue(s3ObjectExists(s3, subpath + "/" + snapshotName + "/"),
                "Per-snapshot marker should exist after auto-discovery backup");
            var keys = listS3Keys(s3, subpath + "/" + snapshotName + "/");
            log.info("S3 keys after auto-discover backup: {}", keys);
            Assertions.assertFalse(keys.isEmpty(),
                "Backup should succeed with auto-discovered collections");
        }
    }

    // -- S3 directory marker metadata and downstream-reader layout verification --

    @Test
    void cloudBackup_directoryMarkerHasCorrectMetadata() throws Exception {
        String collName = "marker_meta_coll";
        createCollection(collName, 1);
        indexDocs(collName, 3);

        String subpath = "marker-meta-test";
        String snapshotName = "backup_marker_meta";
        var args = makeArgs("s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
        args.solrCollections = List.of(collName);

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext()).run();

        // Verify the directory markers created by ensureS3LocationExists have the exact
        // metadata that Solr's S3BackupRepository HeadObject checks expect.
        try (var s3 = testS3Client()) {
            // Parent marker: <subpath>/
            HeadObjectResponse parentMarker = s3.headObject(HeadObjectRequest.builder()
                .bucket(BUCKET_NAME).key(subpath + "/").build());
            Assertions.assertEquals("application/x-directory", parentMarker.contentType(),
                "Parent marker must have content-type application/x-directory");
            Assertions.assertEquals(0L, parentMarker.contentLength(),
                "Parent marker must be zero bytes");

            // Per-snapshot marker: <subpath>/<snapshotName>/
            HeadObjectResponse snapMarker = s3.headObject(HeadObjectRequest.builder()
                .bucket(BUCKET_NAME).key(subpath + "/" + snapshotName + "/").build());
            Assertions.assertEquals("application/x-directory", snapMarker.contentType(),
                "Per-snapshot marker must have content-type application/x-directory");
            Assertions.assertEquals(0L, snapMarker.contentLength(),
                "Per-snapshot marker must be zero bytes");
        }
    }

    @Test
    void cloudBackup_producesExpectedLayoutForDownstreamReaders() throws Exception {
        // Solr 8 incremental backup (name=<collection>, location=<base>/<snapshotName>) writes:
        //   <base>/<snapshotName>/<collection>/<collection>/zk_backup_0/...
        // The outer <collection> is the backup `name` directory; the inner one is Solr's
        // per-collection data directory.  Downstream readers (SolrBackupLayout, S3Repo)
        // must account for this two-level structure.
        String collName = "layout_verify_coll";
        createCollection(collName, 1);
        indexDocs(collName, 5);

        String subpath = "layout-verify";
        String snapshotName = "backup_layout";
        var args = makeArgs("s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
        args.solrCollections = List.of(collName);

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext()).run();

        try (var s3 = testS3Client()) {
            // Solr writes: <subpath>/<snap>/<name>/<collection>/files
            // With name=collection this becomes <subpath>/<snap>/<coll>/<coll>/files
            String backupNamePrefix = subpath + "/" + snapshotName + "/" + collName + "/";
            String collDataPrefix = backupNamePrefix + collName + "/";
            var keys = listS3Keys(s3, backupNamePrefix);
            log.info("All keys under backup name '{}': {}", collName, keys);

            // 1) zk_backup_0/ must exist (schema metadata for MetadataMigration)
            Assertions.assertTrue(
                keys.stream().anyMatch(k -> k.startsWith(collDataPrefix + "zk_backup_0/")),
                "Backup must contain zk_backup_0/ for metadata migration; keys=" + keys);

            // 2) shard_backup_metadata/ must exist (shard->file mappings for document backfill)
            Assertions.assertTrue(
                keys.stream().anyMatch(k -> k.startsWith(collDataPrefix + "shard_backup_metadata/")),
                "Backup must contain shard_backup_metadata/ for document backfill; keys=" + keys);

            // 3) backup_0.properties must exist (collection discovery marker)
            Assertions.assertTrue(
                keys.stream().anyMatch(k -> k.equals(collDataPrefix + "backup_0.properties")),
                "Backup must contain backup_0.properties for collection discovery; keys=" + keys);

            // 4) index/ must exist (the actual Lucene index files)
            Assertions.assertTrue(
                keys.stream().anyMatch(k -> k.startsWith(collDataPrefix + "index/")),
                "Backup must contain index/ directory with Lucene files; keys=" + keys);
        }
    }

    @Test
    void cloudBackup_multiCollection_eachHasFullLayout() throws Exception {
        // Verify the full layout is produced per-collection when backing up multiple
        // collections — catches the old bug where name=<snapshotName> caused only the
        // last collection's metadata to survive (Solr 8 incremental enforces one-collection-per-name).
        // Layout: <subpath>/<snap>/<coll>/<coll>/files (name=collection doubling)
        String coll1 = "layout_multi_a";
        String coll2 = "layout_multi_b";
        createCollection(coll1, 1);
        createCollection(coll2, 2);
        indexDocs(coll1, 4);
        indexDocs(coll2, 7);

        String subpath = "layout-multi";
        String snapshotName = "backup_multi_layout";
        var args = makeArgs("s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
        args.solrCollections = List.of(coll1, coll2);

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext()).run();

        try (var s3 = testS3Client()) {
            for (var coll : List.of(coll1, coll2)) {
                String backupNamePrefix = subpath + "/" + snapshotName + "/" + coll + "/";
                String collDataPrefix = backupNamePrefix + coll + "/";
                var keys = listS3Keys(s3, backupNamePrefix);
                log.info("Keys for collection '{}': {}", coll, keys);

                Assertions.assertTrue(
                    keys.stream().anyMatch(k -> k.startsWith(collDataPrefix + "zk_backup_0/")),
                    coll + " must have zk_backup_0/; keys=" + keys);
                Assertions.assertTrue(
                    keys.stream().anyMatch(k -> k.startsWith(collDataPrefix + "shard_backup_metadata/")),
                    coll + " must have shard_backup_metadata/; keys=" + keys);
                Assertions.assertTrue(
                    keys.stream().anyMatch(k -> k.equals(collDataPrefix + "backup_0.properties")),
                    coll + " must have backup_0.properties; keys=" + keys);
                Assertions.assertTrue(
                    keys.stream().anyMatch(k -> k.startsWith(collDataPrefix + "index/")),
                    coll + " must have index/; keys=" + keys);
            }
        }
    }

    @Test
    void cloudBackup_bucketRoot_noParentMarkerCreated() throws Exception {
        // When the URI is s3://bucket (no subpath), ensureS3LocationExists should skip the
        // parent marker (key is empty/root) and only create the per-snapshot marker.
        String collName = "bucket_root_marker_coll";
        createCollection(collName, 1);
        indexDocs(collName, 3);

        String snapshotName = "backup_root_marker_check";
        var args = makeArgs("s3://" + BUCKET_NAME, snapshotName);
        args.solrCollections = List.of(collName);

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext()).run();

        try (var s3 = testS3Client()) {
            // Per-snapshot marker should exist
            Assertions.assertTrue(s3ObjectExists(s3, snapshotName + "/"),
                "Per-snapshot marker should exist at bucket root");

            // No spurious "/" directory marker (bucket root = no parent key to mark)
            Assertions.assertFalse(s3ObjectExists(s3, "/"),
                "No directory marker should be created for bucket root path '/'");

            // Verify actual backup data landed under <snap>/<coll>/<coll>/
            String collDataPrefix = snapshotName + "/" + collName + "/" + collName + "/";
            var keys = listS3Keys(s3, snapshotName + "/");
            Assertions.assertTrue(
                keys.stream().anyMatch(k -> k.startsWith(collDataPrefix + "zk_backup_0/")),
                "Bucket root backup must contain collection data; keys=" + keys);
        }
    }

    @Test
    void cloudBackup_successiveSnapshots_bothHaveFullLayout() throws Exception {
        // Take two snapshots to the same base path and verify both produce the full
        // backup structure.  Layout: <subpath>/<snap>/<coll>/<coll>/files
        String collName = "successive_layout_coll";
        createCollection(collName, 1);
        indexDocs(collName, 3);

        String subpath = "successive-layout";

        // First snapshot
        var args1 = makeArgs("s3://" + BUCKET_NAME + "/" + subpath, "snap_first");
        args1.solrCollections = List.of(collName);
        var ctx1 = SnapshotTestContext.factory().noOtelTracking();
        new CreateSnapshot(args1, ctx1.createSnapshotCreateContext()).run();

        // Add more docs, second snapshot
        indexDocs(collName, 5);
        var args2 = makeArgs("s3://" + BUCKET_NAME + "/" + subpath, "snap_second");
        args2.solrCollections = List.of(collName);
        var ctx2 = SnapshotTestContext.factory().noOtelTracking();
        new CreateSnapshot(args2, ctx2.createSnapshotCreateContext()).run();

        try (var s3 = testS3Client()) {
            for (var snap : List.of("snap_first", "snap_second")) {
                String backupNamePrefix = subpath + "/" + snap + "/" + collName + "/";
                String collDataPrefix = backupNamePrefix + collName + "/";
                var keys = listS3Keys(s3, backupNamePrefix);
                log.info("Keys for snapshot '{}': {}", snap, keys);

                Assertions.assertTrue(
                    keys.stream().anyMatch(k -> k.startsWith(collDataPrefix + "zk_backup_0/")),
                    snap + " must have zk_backup_0/; keys=" + keys);
                Assertions.assertTrue(
                    keys.stream().anyMatch(k -> k.equals(collDataPrefix + "backup_0.properties")),
                    snap + " must have backup_0.properties; keys=" + keys);
                Assertions.assertTrue(
                    keys.stream().anyMatch(k -> k.startsWith(collDataPrefix + "index/")),
                    snap + " must have index/; keys=" + keys);
            }
        }
    }

    // -- S3 helper branch-coverage tests --

    /**
     * Exercise the "endpoint without scheme" branch of {@link SolrBackupStrategy#buildS3Client}.
     * Production code prefixes {@code http://} when the endpoint string has no {@code ://}.
     * LocalStack gives us {@code http://host:port}; we strip the scheme before handing it to
     * CreateSnapshot so buildS3Client has to re-add it. The rest of the path still runs
     * end-to-end against the real Solr + LocalStack containers.
     */
    @Test
    void cloudBackup_endpointWithoutScheme_succeeds() throws Exception {
        String collName = "noscheme_coll";
        createCollection(collName, 1);
        indexDocs(collName, 4);

        String subpath = "noscheme-endpoint";
        String snapshotName = "backup_noscheme";
        var args = makeArgs("s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
        args.solrCollections = List.of(collName);
        // Strip the scheme so buildS3Client has to prefix "http://" itself.
        String endpoint = localStackEndpoint();
        int schemeIdx = endpoint.indexOf("://");
        Assertions.assertTrue(schemeIdx > 0, "LocalStack endpoint should have a scheme");
        args.s3Endpoint = endpoint.substring(schemeIdx + 3);

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext()).run();

        try (var s3 = testS3Client()) {
            Assertions.assertTrue(s3ObjectExists(s3, subpath + "/"),
                "Parent marker should exist when endpoint passed without scheme");
            Assertions.assertTrue(s3ObjectExists(s3, subpath + "/" + snapshotName + "/"),
                "Per-snapshot marker should exist when endpoint passed without scheme");
            var keys = listS3Keys(s3, subpath + "/" + snapshotName + "/" + collName + "/");
            Assertions.assertFalse(keys.isEmpty(),
                "Backup data should land when endpoint has no scheme; keys=" + keys);
        }
    }

    /**
     * Exercise the "both markers already exist" branches of
     * {@link SolrBackupStrategy#createDirectoryMarkerIfMissing}. Pre-create the parent and
     * the per-snapshot markers, then run backup. Neither put should happen for those keys.
     * We verify via content-length: we pre-write 1 byte so a rogue overwrite to
     * {@code application/x-directory} + 0 bytes would be detectable.
     */
    @Test
    void cloudBackup_bothMarkersPreExist_idempotent() throws Exception {
        String collName = "preexist_both_coll";
        createCollection(collName, 1);
        indexDocs(collName, 2);

        String subpath = "preexist-both";
        String snapshotName = "backup_preexist_both";
        var args = makeArgs("s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
        args.solrCollections = List.of(collName);

        // Pre-write BOTH markers with a 1-byte payload and a different content-type so we can
        // detect if the production code overwrites them (it shouldn't — skip branch should hit).
        byte[] sentinel = new byte[] { (byte) 0x42 };
        try (var s3 = testS3Client()) {
            s3.putObject(PutObjectRequest.builder()
                    .bucket(BUCKET_NAME).key(subpath + "/")
                    .contentType("text/plain").build(),
                RequestBody.fromBytes(sentinel));
            s3.putObject(PutObjectRequest.builder()
                    .bucket(BUCKET_NAME).key(subpath + "/" + snapshotName + "/")
                    .contentType("text/plain").build(),
                RequestBody.fromBytes(sentinel));
        }

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext()).run();

        try (var s3 = testS3Client()) {
            var parent = s3.headObject(HeadObjectRequest.builder()
                .bucket(BUCKET_NAME).key(subpath + "/").build());
            Assertions.assertEquals(1L, parent.contentLength(),
                "Pre-existing parent marker must not be overwritten by the skip branch");
            Assertions.assertEquals("text/plain", parent.contentType(),
                "Pre-existing parent content-type must be preserved");

            var snap = s3.headObject(HeadObjectRequest.builder()
                .bucket(BUCKET_NAME).key(subpath + "/" + snapshotName + "/").build());
            Assertions.assertEquals(1L, snap.contentLength(),
                "Pre-existing snapshot marker must not be overwritten by the skip branch");
            Assertions.assertEquals("text/plain", snap.contentType(),
                "Pre-existing snapshot content-type must be preserved");

            // Backup itself should still succeed — downstream data present.
            var keys = listS3Keys(s3, subpath + "/" + snapshotName + "/" + collName + "/");
            Assertions.assertFalse(keys.isEmpty(),
                "Backup should succeed even when both markers already exist; keys=" + keys);
        }
    }

    /**
     * Contract test: the {@code backup_0.properties} file we assert exists in other tests must
     * carry specific keys so downstream readers (MetadataMigration / RFS) can parse it. This
     * fetches the object body and validates minimal required fields. It's the only test in
     * this class that downloads snapshot content rather than just listing keys.
     */
    @Test
    void cloudBackup_backupPropertiesFileHasExpectedKeys() throws Exception {
        String collName = "props_coll";
        createCollection(collName, 1);
        indexDocs(collName, 2);

        String subpath = "props-test";
        String snapshotName = "backup_props";
        var args = makeArgs("s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
        args.solrCollections = List.of(collName);

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext()).run();

        String propsKey = subpath + "/" + snapshotName + "/" + collName + "/" + collName
            + "/backup_0.properties";
        String body;
        try (var s3 = testS3Client();
             var stream = s3.getObject(GetObjectRequest.builder()
                 .bucket(BUCKET_NAME).key(propsKey).build())) {
            body = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        log.info("backup_0.properties contents for {}:\n{}", collName, body);

        // The properties file is the collection-discovery marker. Downstream SolrBackupLayout
        // parses it to find `collection` and `indexVersion` (Solr 8 incremental format).
        Assertions.assertTrue(body.contains("collection=" + collName)
                || body.contains("collection\\=" + collName),
            "backup_0.properties must declare collection=" + collName + "; body=" + body);
        Assertions.assertTrue(body.contains("indexVersion="),
            "backup_0.properties must declare indexVersion=...; body=" + body);
        // Solr 8 incremental backups tag the backup with a numeric id used for "backup_<n>.properties"
        Assertions.assertTrue(body.contains("backupId=") || body.contains("startTime="),
            "backup_0.properties must include backupId or startTime; body=" + body);
    }

    /**
     * Contract test for the downstream reader's directory-listing path: the writer layout must
     * produce S3 common prefixes that exactly match the collection set when listed with
     * delimiter="/". This mirrors what the S3Repo / RfsMigrateDocuments code does when it
     * enumerates collections under a snapshot root.
     */
    @Test
    void cloudBackup_delimiterListingReturnsEachCollectionAsCommonPrefix() throws Exception {
        String coll1 = "delim_coll_a";
        String coll2 = "delim_coll_b";
        String coll3 = "delim_coll_c";
        createCollection(coll1, 1);
        createCollection(coll2, 1);
        createCollection(coll3, 2);
        indexDocs(coll1, 3);
        indexDocs(coll2, 4);
        indexDocs(coll3, 5);

        String subpath = "delim-listing";
        String snapshotName = "backup_delim";
        var args = makeArgs("s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
        args.solrCollections = List.of(coll1, coll2, coll3);

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext()).run();

        String snapRoot = subpath + "/" + snapshotName + "/";
        try (var s3 = testS3Client()) {
            ListObjectsV2Response resp = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(BUCKET_NAME)
                .prefix(snapRoot)
                .delimiter("/")
                .build());
            var commonPrefixes = resp.commonPrefixes().stream()
                .map(p -> p.prefix())
                .toList();
            log.info("Common prefixes under '{}': {}", snapRoot, commonPrefixes);

            // Each collection should appear exactly once as a child "directory" of the snapshot.
            for (String coll : List.of(coll1, coll2, coll3)) {
                String expected = snapRoot + coll + "/";
                Assertions.assertTrue(commonPrefixes.contains(expected),
                    "Delimiter listing must expose collection '" + coll
                        + "' as common prefix '" + expected + "'; got=" + commonPrefixes);
            }
            // No extra top-level siblings — only the three collections.
            Assertions.assertEquals(3, commonPrefixes.size(),
                "Snapshot root should have exactly one common prefix per collection; got="
                    + commonPrefixes);
        }
    }
}
