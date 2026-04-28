package org.opensearch.migrations;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.solr.SolrHttpClient;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;
import org.opensearch.migrations.testutils.CloseableLogSetup;

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
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
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
import software.amazon.awssdk.services.s3.model.S3Exception;

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
            body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
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

    /**
     * G1: When the S3 bucket referenced by s3RepoUri does not exist, {@code ensureS3LocationExists}
     * must swallow the resulting exception (HeadObject/PutObject against a missing bucket throws
     * a non-NoSuchKey S3Exception, exercising the outer {@code catch (Exception e)} branch that
     * rethrow-cases in {@code s3DirectoryMarkerExists} ultimately fall into) and emit a WARN
     * log. The surrounding cloud backup will then proceed and fail downstream when Solr itself
     * tries to access the bucket — but the marker helper must not abort the run.
     *
     * <p>Net effect verified: (a) the warn-log path fires with the expected marker message
     * prefix, (b) Solr's subsequent BACKUP call fails (bucket still missing), and (c) no marker
     * objects were created in the real bucket. This is the dual of G4 — the outer catch
     * swallows anything that propagates from the helper stack.
     */
    @Test
    void cloudBackup_s3LocationMarkerFailure_continuesToBackup() throws Exception {
        String collName = "nonexistent_bucket_coll";
        createCollection(collName, 1);
        indexDocs(collName, 2);

        String missingBucket = "no-such-bucket-" + System.nanoTime();
        String snapshotName = "backup_missing_bucket";
        var args = makeArgs("s3://" + missingBucket + "/anyprefix", snapshotName);
        args.solrCollections = List.of(collName);

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        try (var logCapture = new CloseableLogSetup(SolrBackupStrategy.class.getName())) {
            var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
            // Expect the overall run to fail when Solr's BACKUP attempts to write to a missing
            // bucket, but the pre-flight marker creation must not short-circuit it with a throw.
            Assertions.assertThrows(Exception.class, creator::run,
                "Backup should ultimately fail when S3 bucket does not exist");

            boolean sawWarn = logCapture.getLogEvents().stream()
                .anyMatch(m -> m.contains("Failed to ensure S3 directory markers")
                    && m.contains(missingBucket));
            Assertions.assertTrue(sawWarn,
                "Expected WARN log from ensureS3LocationExists for missing bucket; got events: "
                    + logCapture.getLogEvents());
        }

        // No marker objects should have been created in any *real* bucket (smoke-check our
        // test bucket is untouched at the random prefix).
        try (var s3 = testS3Client()) {
            Assertions.assertFalse(s3ObjectExists(s3, "anyprefix/"),
                "No marker should exist in the real test bucket at the random prefix");
        }
    }

    /**
     * G2: {@code buildS3Client(region, endpoint)} is private+static; exercise the branch where
     * endpoint is null or empty — the builder must NOT apply {@code endpointOverride} in that
     * case (relying on the SDK's default regional resolution). Verified by reading back
     * {@code S3Client.serviceClientConfiguration().endpointOverride()} which returns an
     * Optional that is empty when no override was set.
     *
     * <p>Also verifies the complementary happy path: when a non-empty endpoint is passed the
     * Optional is present and carries the expected URI (prefixing with {@code http://} when
     * the scheme is absent).
     */
    @Test
    void buildS3Client_nullOrEmptyEndpoint_usesAwsDefaultResolution() throws Exception {
        Method build = SolrBackupStrategy.class.getDeclaredMethod(
            "buildS3Client", String.class, String.class);
        build.setAccessible(true);

        // null endpoint -> no override
        try (S3Client c = (S3Client) build.invoke(null, "us-east-1", null)) {
            Assertions.assertFalse(c.serviceClientConfiguration().endpointOverride().isPresent(),
                "null endpoint should leave endpointOverride unset");
        }
        // empty endpoint -> no override
        try (S3Client c = (S3Client) build.invoke(null, "us-east-1", "")) {
            Assertions.assertFalse(c.serviceClientConfiguration().endpointOverride().isPresent(),
                "empty endpoint should leave endpointOverride unset");
        }
        // explicit endpoint without scheme -> wrapped in http:// and applied
        try (S3Client c = (S3Client) build.invoke(null, "us-east-1", "localhost:4566")) {
            var opt = c.serviceClientConfiguration().endpointOverride();
            Assertions.assertTrue(opt.isPresent(), "endpoint should be applied when non-empty");
            Assertions.assertEquals("http://localhost:4566", opt.get().toString(),
                "endpoint without scheme must be prefixed with http://");
        }
        // explicit endpoint with scheme -> passed through as-is
        try (S3Client c = (S3Client) build.invoke(null, "us-east-1", "https://s3.example.com")) {
            var opt = c.serviceClientConfiguration().endpointOverride();
            Assertions.assertTrue(opt.isPresent(), "endpoint should be applied when non-empty");
            Assertions.assertEquals("https://s3.example.com", opt.get().toString(),
                "endpoint with scheme must be used verbatim");
        }
    }

    /**
     * G3: Pre-create ONLY the per-snapshot directory marker {@code <parent>/<snapshot>/} before
     * invoking the backup. The parent prefix marker {@code <parent>/} should not exist yet.
     * The cloud backup path must detect the snapshot marker as already-present (exercising the
     * "already exists" log branch in {@code createDirectoryMarkerIfMissing}) AND create the
     * missing parent marker. Inverse of
     * {@link #cloudBackupToSubpath_preExistingMarker_idempotentAndSucceeds}.
     */
    @Test
    void cloudBackup_onlySnapshotMarkerPreExists_parentCreated() throws Exception {
        String collName = "preexist_snap_only_coll";
        createCollection(collName, 1);
        indexDocs(collName, 3);

        String subpath = "preexist-snap-only";
        String snapshotName = "backup_snap_only";
        var args = makeArgs("s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
        args.solrCollections = List.of(collName);

        // Pre-create ONLY the per-snapshot marker; the parent subpath marker does NOT exist yet.
        try (var s3 = testS3Client()) {
            s3.putObject(PutObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(subpath + "/" + snapshotName + "/")
                    .contentType("application/x-directory")
                    .build(),
                RequestBody.empty());
            Assertions.assertFalse(s3ObjectExists(s3, subpath + "/"),
                "Parent marker should NOT exist yet (test precondition)");
            Assertions.assertTrue(s3ObjectExists(s3, subpath + "/" + snapshotName + "/"),
                "Snapshot marker precondition failed");
        }

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        try (var logCapture = new CloseableLogSetup(SolrBackupStrategy.class.getName())) {
            new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext()).run();

            // Parent marker must have been created; snapshot marker hit the "already exists" path.
            long alreadyExistsLogCount = logCapture.getLogEvents().stream()
                .filter(m -> m.contains("S3 directory marker already exists"))
                .count();
            Assertions.assertTrue(alreadyExistsLogCount >= 1,
                "Expected at least one 'already exists' log for the snapshot marker; got: "
                    + logCapture.getLogEvents());
            boolean createdParent = logCapture.getLogEvents().stream()
                .anyMatch(m -> m.contains("Created S3 directory marker")
                    && m.contains(subpath + "/")
                    && !m.contains(snapshotName));
            Assertions.assertTrue(createdParent,
                "Expected 'Created S3 directory marker' for parent prefix; got: "
                    + logCapture.getLogEvents());
        }

        try (var s3 = testS3Client()) {
            Assertions.assertTrue(s3ObjectExists(s3, subpath + "/"),
                "Parent marker should have been created by ensureS3LocationExists");
            var keys = listS3Keys(s3, subpath + "/" + snapshotName + "/");
            Assertions.assertFalse(keys.isEmpty(),
                "Backup should have written data under the pre-existing snapshot marker");
        }
    }

    /**
     * G5: Exercise {@code s3DirectoryMarkerExists} third catch branch — an {@link S3Exception}
     * whose {@code statusCode()} is NOT 404 must be rethrown (not swallowed as "marker
     * absent"). We invoke the private method reflectively via a JDK dynamic proxy S3Client that
     * throws a synthesized 403 S3Exception from {@code headObject}. The method is expected to
     * propagate the exception — and callers (ensureS3LocationExists) then catch it in their
     * outer try/catch and log a warning, which is what allows a Deny-HeadObject scenario to
     * surface as a "Failed to ensure S3 directory markers" warn rather than silent false.
     */
    @Test
    void s3DirectoryMarkerExists_non404S3Exception_isRethrown() throws Exception {
        Method exists = SolrBackupStrategy.class.getDeclaredMethod(
            "s3DirectoryMarkerExists", S3Client.class, String.class, String.class);
        exists.setAccessible(true);

        S3Exception status403 = (S3Exception) S3Exception.builder()
            .statusCode(403)
            .message("Access Denied (synthesized)")
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build())
            .build();

        S3Client throwingClient = (S3Client) Proxy.newProxyInstance(
            S3Client.class.getClassLoader(),
            new Class<?>[] {S3Client.class},
            (proxy, m, a) -> {
                if ("headObject".equals(m.getName())) {
                    throw status403;
                }
                if ("close".equals(m.getName())) {
                    return null;
                }
                // Any other method is unexpected for this test.
                throw new UnsupportedOperationException("Unexpected call: " + m.getName());
            });

        // Construct via the normal ctor with a valid Args — the method under test reads no
        // instance fields, so the constructor side-effects don't affect the assertion.
        var strategy = new SolrBackupStrategy(makeArgs("s3://" + BUCKET_NAME + "/ignored", "ignored"));

        InvocationTargetException ite = Assertions.assertThrows(InvocationTargetException.class,
            () -> exists.invoke(strategy, throwingClient, "some-bucket", "some/key/"));
        Assertions.assertTrue(ite.getCause() instanceof S3Exception,
            "Expected S3Exception to be rethrown for non-404 status; got: " + ite.getCause());
        Assertions.assertEquals(403, ((S3Exception) ite.getCause()).statusCode(),
            "Rethrown exception should preserve the original 403 status code");
    }

    /**
     * G6: Exercise {@code s3DirectoryMarkerExists} second catch branch — a plain
     * {@link S3Exception} with {@code statusCode()==404} that is NOT a {@link NoSuchKeyException}
     * must be treated as "marker absent" (return false). Some S3-compatible stores and certain
     * SDK wrapping layers surface missing-object as a bare S3Exception with status 404 rather
     * than the typed subclass; the fall-through branch exists specifically for that case. This
     * complements {@link #s3DirectoryMarkerExists_non404S3Exception_isRethrown} which covers the
     * rethrow side of the same 3-way catch.
     */
    @Test
    void s3DirectoryMarkerExists_status404S3Exception_treatedAsAbsent() throws Exception {
        Method exists = SolrBackupStrategy.class.getDeclaredMethod(
            "s3DirectoryMarkerExists", S3Client.class, String.class, String.class);
        exists.setAccessible(true);

        S3Exception status404 = (S3Exception) S3Exception.builder()
            .statusCode(404)
            .message("Not Found (synthesized, non-NoSuchKey)")
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("NotFound").build())
            .build();

        S3Client throwingClient = (S3Client) Proxy.newProxyInstance(
            S3Client.class.getClassLoader(),
            new Class<?>[] {S3Client.class},
            (proxy, m, a) -> {
                if ("headObject".equals(m.getName())) {
                    throw status404;
                }
                if ("close".equals(m.getName())) {
                    return null;
                }
                throw new UnsupportedOperationException("Unexpected call: " + m.getName());
            });

        var strategy = new SolrBackupStrategy(makeArgs("s3://" + BUCKET_NAME + "/ignored", "ignored"));
        Object result = exists.invoke(strategy, throwingClient, "some-bucket", "some/key/");
        Assertions.assertEquals(Boolean.FALSE, result,
            "s3DirectoryMarkerExists should return false for a 404 S3Exception (not rethrow)");
    }

    /**
     * Helper: Solr caches async task status by request ID. If we reuse the same async ID across
     * two BACKUP calls (as CreateSnapshot does, using snapshotName-collection), Solr short-circuits
     * the second call and returns the cached "completed" status without running a new backup.
     * Call this between runs to clear the cache so the second BACKUP actually executes.
     *
     * <p>Mirrors the synchronous-curl workaround used by {@code SolrSuccessiveBackupsTest}, but
     * reuses our {@link SolrHttpClient} so the call flows through the same connection pool.
     */
    private static void deleteSolrAsyncStatus(String asyncId) {
        try {
            solrHttpClient().getJson(solrUrl()
                + "/solr/admin/collections?action=DELETESTATUS&requestid="
                + asyncId + "&wt=json");
        } catch (Exception e) {
            // Best-effort — if the task doesn't exist, Solr returns an error we can ignore.
            log.atWarn().setMessage("DELETESTATUS for asyncId={} failed: {}")
                .addArgument(asyncId).addArgument(e.getMessage()).log();
        }
    }

    /**
     * G7: Back-to-back backups to the SAME snapshot name. Solr 8 incremental backup does not
     * fail on a duplicate name — it appends successive revisions:
     *   zk_backup_0/, zk_backup_1/, backup_0.properties, backup_1.properties, md_shard1_0.json, md_shard1_1.json
     * This test is the only one in this file that actually produces a {@code zk_backup_1} directory
     * in LocalStack through the real CreateSnapshot code path (SolrSuccessiveBackupsTest produces
     * one too, but on a local-FS backup and via direct curl, bypassing our CreateSnapshot wrapper
     * and S3 entirely). Our downstream {@link org.opensearch.migrations.bulkload.solr.SolrBackupLayout}
     * helpers ({@code findLatestZkBackup}, {@code findLatestShardMetadataFiles}) are written to
     * traverse these revisions, so verifying the S3 artifacts exist closes the loop.
     *
     * <p>This also naturally exercises the "both markers already exist" skip branches in
     * {@code createDirectoryMarkerIfMissing} without a synthetic setup — on the second run the
     * parent and per-snapshot markers are both present from run #1.
     *
     * <p>NOTE: We must clear Solr's async-task status between runs. CreateSnapshot uses
     * {@code async=<snapshotName>-<collection>} as the request ID, and Solr caches completed
     * task status indefinitely; without a DELETESTATUS the second BACKUP call is a no-op that
     * returns the cached "completed" state without running.
     */
    @Test
    void cloudBackup_sameSnapshotNameTwice_appendsIncrementalRevisions() throws Exception {
        String collName = "incremental_coll";
        createCollection(collName, 1);
        indexDocs(collName, 3);

        String subpath = "incremental-revisions";
        String snapshotName = "backup_same_name";
        var args = makeArgs("s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
        args.solrCollections = List.of(collName);

        // Run 1
        new CreateSnapshot(args, SnapshotTestContext.factory().noOtelTracking()
            .createSnapshotCreateContext()).run();

        // Clear Solr's async-task cache so run 2's BACKUP isn't short-circuited.
        deleteSolrAsyncStatus(
            org.opensearch.migrations.bulkload.solr.SolrSnapshotCreator.asyncIdFor(
                snapshotName, collName));

        // Add more docs so run 2 produces a materially different snapshot
        indexDocs(collName, 5);

        // Run 2 — same s3RepoUri, same snapshotName. Expect "already exists" skip-branches on
        // BOTH markers, and a new revision under the same collection data prefix.
        try (var logCapture = new CloseableLogSetup(SolrBackupStrategy.class.getName())) {
            new CreateSnapshot(args, SnapshotTestContext.factory().noOtelTracking()
                .createSnapshotCreateContext()).run();

            long alreadyExistsLogs = logCapture.getLogEvents().stream()
                .filter(m -> m.contains("S3 directory marker already exists"))
                .count();
            Assertions.assertTrue(alreadyExistsLogs >= 2,
                "Second run should hit the 'already exists' skip branch for BOTH markers "
                    + "(parent + per-snapshot); got count=" + alreadyExistsLogs
                    + ", events=" + logCapture.getLogEvents());
        }

        String collDataPrefix = subpath + "/" + snapshotName + "/" + collName + "/" + collName + "/";
        try (var s3 = testS3Client()) {
            var keys = listS3Keys(s3, collDataPrefix);
            log.info("Keys after two snapshots to the same name: {}", keys);

            // Solr appends successive revisions rather than overwriting.
            Assertions.assertTrue(
                keys.stream().anyMatch(k -> k.startsWith(collDataPrefix + "zk_backup_0/")),
                "zk_backup_0/ (first revision) must exist; keys=" + keys);
            Assertions.assertTrue(
                keys.stream().anyMatch(k -> k.startsWith(collDataPrefix + "zk_backup_1/")),
                "zk_backup_1/ (second revision, produced by same-name re-run) must exist; "
                    + "this is the key fact SolrBackupLayout.findLatestZkBackup relies on; "
                    + "keys=" + keys);
            Assertions.assertTrue(
                keys.stream().anyMatch(k -> k.equals(collDataPrefix + "backup_0.properties")),
                "backup_0.properties must exist; keys=" + keys);
            Assertions.assertTrue(
                keys.stream().anyMatch(k -> k.equals(collDataPrefix + "backup_1.properties")),
                "backup_1.properties (second revision) must exist; keys=" + keys);

            // Shard metadata picks up a new revision too — md_shard1_0.json AND md_shard1_1.json.
            // SolrBackupLayout.findLatestShardMetadataFiles must pick the _1 file for this shard.
            var shardMetaKeys = keys.stream()
                .filter(k -> k.contains("/shard_backup_metadata/md_shard1_"))
                .toList();
            Assertions.assertTrue(shardMetaKeys.stream().anyMatch(k -> k.endsWith("_0.json")),
                "md_shard1_0.json must exist after run #1; keys=" + shardMetaKeys);
            Assertions.assertTrue(shardMetaKeys.stream().anyMatch(k -> k.endsWith("_1.json")),
                "md_shard1_1.json must exist after run #2 (same snapshot name); "
                    + "keys=" + shardMetaKeys);
        }
    }

    /**
     * G8: Multi-shard variant of the same-name successive-revision test. Solr 8 increments the
     * per-shard metadata counter independently for each shard, so a 2-shard collection that gets
     * backed up to the same snapshot name twice produces md_shard1_0, md_shard1_1, md_shard2_0,
     * md_shard2_1. SolrBackupLayout.findLatestShardMetadataFiles is expected to return the
     * highest N per shard — without this test nothing in the repo verifies that Solr actually
     * increments per-shard counters (as opposed to a single global counter). This distinction
     * matters for the downstream reader: a single global counter would mean md_shard1_1 and
     * md_shard2_1 point at the same revision, which would break shard-parallel discovery.
     */
    @Test
    void cloudBackup_sameSnapshotNameTwice_multiShard_perShardRevisionCounters() throws Exception {
        String collName = "incremental_multishard_coll";
        createCollection(collName, 2);
        indexDocs(collName, 12);

        String subpath = "incremental-multishard";
        String snapshotName = "backup_multishard_same_name";
        var args = makeArgs("s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
        args.solrCollections = List.of(collName);

        // Run 1
        new CreateSnapshot(args, SnapshotTestContext.factory().noOtelTracking()
            .createSnapshotCreateContext()).run();

        // Clear Solr's async-task cache before run 2 (see deleteSolrAsyncStatus javadoc).
        deleteSolrAsyncStatus(
            org.opensearch.migrations.bulkload.solr.SolrSnapshotCreator.asyncIdFor(
                snapshotName, collName));

        // Index more docs, run 2 to the same name
        indexDocs(collName, 8);
        new CreateSnapshot(args, SnapshotTestContext.factory().noOtelTracking()
            .createSnapshotCreateContext()).run();

        String collDataPrefix = subpath + "/" + snapshotName + "/" + collName + "/" + collName + "/";
        try (var s3 = testS3Client()) {
            var shardMetaKeys = listS3Keys(s3, collDataPrefix + "shard_backup_metadata/");
            log.info("Shard metadata keys after two multi-shard snapshots: {}", shardMetaKeys);

            // Expect md_shard1_{0,1}.json AND md_shard2_{0,1}.json — per-shard counter, not global.
            for (String shard : List.of("shard1", "shard2")) {
                Assertions.assertTrue(
                    shardMetaKeys.stream().anyMatch(k -> k.endsWith("/md_" + shard + "_0.json")),
                    shard + " must have rev 0 metadata; keys=" + shardMetaKeys);
                Assertions.assertTrue(
                    shardMetaKeys.stream().anyMatch(k -> k.endsWith("/md_" + shard + "_1.json")),
                    shard + " must have rev 1 metadata (same-name re-run); "
                        + "keys=" + shardMetaKeys);
            }

            // Both revisions of the top-level properties file should coexist.
            var propsKeys = listS3Keys(s3, collDataPrefix).stream()
                .filter(k -> k.endsWith(".properties"))
                .toList();
            Assertions.assertTrue(propsKeys.stream()
                    .anyMatch(k -> k.endsWith("/backup_0.properties")),
                "backup_0.properties should exist; keys=" + propsKeys);
            Assertions.assertTrue(propsKeys.stream()
                    .anyMatch(k -> k.endsWith("/backup_1.properties")),
                "backup_1.properties should exist; keys=" + propsKeys);
        }
    }
}
