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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.wait.strategy.Wait;
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
 * Each test method starts fresh containers to guarantee deterministic, isolated state.
 *
 * These tests run under the {@code isolatedTest} Gradle task (tag: isolatedTest).
 */
@Slf4j
@Tag("isolatedTest")
public class TestCreateSnapshotSolrS3 {

    private static final String BUCKET_NAME = "solr-backup-test";
    private static final String REGION = "us-east-1";
    private static final String LOCALSTACK_ALIAS = "localstack";

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

    private static class TestEnv implements AutoCloseable {
        final Network network;
        final LocalStackContainer localStack;
        final GenericContainer<?> solrCloud;

        TestEnv() throws Exception {
            network = Network.newNetwork();
            localStack = new LocalStackContainer(
                    DockerImageName.parse("localstack/localstack:4.3.0"))
                .withServices(LocalStackContainer.Service.S3)
                .withNetwork(network)
                .withNetworkAliases(LOCALSTACK_ALIAS);
            localStack.start();

            solrCloud = new GenericContainer<>(
                    DockerImageName.parse("solr:8.11.4"))
                .withNetwork(network)
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
            solrCloud.start();

            localStack.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_NAME);
            log.info("Created S3 bucket '{}' in LocalStack", BUCKET_NAME);
        }

        String solrUrl() {
            return "http://" + solrCloud.getHost() + ":" + solrCloud.getMappedPort(8983);
        }

        String localStackEndpoint() {
            return localStack.getEndpoint().toString();
        }

        S3Client testS3Client() {
            return S3Client.builder()
                .region(Region.of(REGION))
                .endpointOverride(localStack.getEndpoint())
                .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test", "test")))
                .forcePathStyle(true)
                .build();
        }

        SolrHttpClient solrHttpClient() {
            return new SolrHttpClient(connectionContext());
        }

        ConnectionContext connectionContext() {
            var args = new ConnectionContext.SourceArgs();
            args.host = solrUrl();
            args.insecure = true;
            return args.toConnectionContext();
        }

        void createCollection(String name, int numShards) throws Exception {
            int maxShards = Math.max(numShards, 1);
            SOLR_EXEC(
                "http://localhost:8983/solr/admin/collections?action=CREATE"
                    + "&name=" + name
                    + "&numShards=" + numShards
                    + "&replicationFactor=1"
                    + "&maxShardsPerNode=" + maxShards
                    + "&wt=json");
            log.info("Created SolrCloud collection '{}' with {} shards", name, numShards);
        }

        void indexDocs(String collection, int count) throws Exception {
            var sb = new StringBuilder("[");
            for (int i = 0; i < count; i++) {
                if (i > 0) sb.append(",");
                sb.append("{\"id\":\"doc-").append(i)
                  .append("\",\"title\":\"Test document ").append(i).append("\"}");
            }
            sb.append("]");
            var result = solrCloud.execInContainer("curl", "-sf",
                "http://localhost:8983/solr/" + collection + "/update?commit=true",
                "-H", "Content-Type: application/json",
                "-d", sb.toString());
            if (result.getExitCode() != 0) {
                throw new RuntimeException("Failed to index docs into " + collection
                    + ": exit=" + result.getExitCode() + " stderr=" + result.getStderr());
            }
            log.info("Indexed {} documents into collection '{}'", count, collection);
        }

        private void SOLR_EXEC(String url) throws Exception {
            var result = solrCloud.execInContainer("curl", "-sf", url);
            if (result.getExitCode() != 0) {
                throw new RuntimeException("Solr command failed: " + url
                    + " exit=" + result.getExitCode() + " stderr=" + result.getStderr());
            }
        }

        void deleteSolrAsyncStatus(String asyncId) {
            try {
                solrHttpClient().getJson(solrUrl()
                    + "/solr/admin/collections?action=DELETESTATUS&requestid="
                    + asyncId + "&wt=json");
            } catch (Exception e) {
                log.atWarn().setMessage("DELETESTATUS for asyncId={} failed: {}")
                    .addArgument(asyncId).addArgument(e.getMessage()).log();
            }
        }

        @Override
        public void close() {
            solrCloud.stop();
            localStack.stop();
            network.close();
        }
    }

    private CreateSnapshot.Args makeArgs(TestEnv env, String s3RepoUri, String snapshotName) {
        var args = new CreateSnapshot.Args();
        args.sourceArgs.host = env != null ? env.solrUrl() : "http://localhost:8983";
        args.sourceArgs.insecure = true;
        args.sourceType = "solr";
        args.snapshotName = snapshotName;
        args.snapshotRepoName = "s3";
        args.s3RepoUri = s3RepoUri;
        args.s3Region = REGION;
        args.s3Endpoint = env != null ? env.localStackEndpoint() : null;
        args.noWait = false;
        return args;
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
        try (var env = new TestEnv()) {
            String collName = "bucket_root_coll";
            env.createCollection(collName, 1);
            env.indexDocs(collName, 10);

            String snapshotName = "backup_root_test";
            var args = makeArgs(env, "s3://" + BUCKET_NAME, snapshotName);
            args.solrCollections = List.of(collName);

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
            creator.run();

            try (var s3 = env.testS3Client()) {
                var keys = listS3Keys(s3, snapshotName + "/");
                log.info("S3 keys under '{}/*/': {}", snapshotName, keys);
                Assertions.assertFalse(keys.isEmpty(),
                    "Backup files should exist under " + snapshotName + "/*/ in S3");
                Assertions.assertTrue(
                    keys.stream().anyMatch(k -> k.startsWith(snapshotName + "/" + collName + "/")),
                    "Collection " + collName + " must be a direct child of snapshot root; keys=" + keys);
            }
        }
    }

    @Test
    void cloudBackupToSubpath_createsMarkerAndBackupSucceeds() throws Exception {
        try (var env = new TestEnv()) {
            String collName = "subpath_coll";
            env.createCollection(collName, 1);
            env.indexDocs(collName, 15);

            String subpath = "migration-v1";
            String snapshotName = "backup_subpath_test";
            var args = makeArgs(env, "s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
            args.solrCollections = List.of(collName);

            try (var s3 = env.testS3Client()) {
                Assertions.assertFalse(s3ObjectExists(s3, subpath + "/"),
                    "Marker should not exist before backup");
            }

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
            creator.run();

            try (var s3 = env.testS3Client()) {
                Assertions.assertTrue(s3ObjectExists(s3, subpath + "/"),
                    "Parent directory marker should exist at " + subpath + "/");
                Assertions.assertTrue(s3ObjectExists(s3, subpath + "/" + snapshotName + "/"),
                    "Per-snapshot marker should exist at " + subpath + "/" + snapshotName + "/");
                var keys = listS3Keys(s3, subpath + "/" + snapshotName + "/");
                log.info("S3 keys under '{}/{}/*/': {}", subpath, snapshotName, keys);
                Assertions.assertFalse(keys.isEmpty(),
                    "Backup files should exist under " + subpath + "/" + snapshotName + "/*/ in S3");
                Assertions.assertTrue(
                    keys.stream().anyMatch(k -> k.startsWith(subpath + "/" + snapshotName + "/" + collName + "/")),
                    "Collection " + collName + " must be a direct child of snapshot root; keys=" + keys);
            }
        }
    }

    @Test
    void cloudBackupToNestedSubpath_createsMarkerAndBackupSucceeds() throws Exception {
        try (var env = new TestEnv()) {
            String collName = "nested_subpath_coll";
            env.createCollection(collName, 1);
            env.indexDocs(collName, 5);

            String subpath = "deep/nested/path";
            String snapshotName = "backup_nested_test";
            var args = makeArgs(env, "s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
            args.solrCollections = List.of(collName);

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
            creator.run();

            try (var s3 = env.testS3Client()) {
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
    }

    @Test
    void cloudBackupToSubpath_multipleCollections_singleMarker() throws Exception {
        try (var env = new TestEnv()) {
            String coll1 = "multi_coll_1";
            String coll2 = "multi_coll_2";
            env.createCollection(coll1, 1);
            env.createCollection(coll2, 1);
            env.indexDocs(coll1, 8);
            env.indexDocs(coll2, 12);

            String subpath = "multi-coll-test";
            String snapshotName = "backup_multi_coll";
            var args = makeArgs(env, "s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
            args.solrCollections = List.of(coll1, coll2);

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
            creator.run();

            try (var s3 = env.testS3Client()) {
                Assertions.assertTrue(s3ObjectExists(s3, subpath + "/"),
                    "Single parent directory marker should exist for multiple collections");
                Assertions.assertTrue(s3ObjectExists(s3, subpath + "/" + snapshotName + "/"),
                    "Per-snapshot marker should exist once regardless of collection count");
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
    }

    @Test
    void cloudBackupToSubpath_multiShard_succeeds() throws Exception {
        try (var env = new TestEnv()) {
            String collName = "multishard_s3_coll";
            env.createCollection(collName, 2);
            env.indexDocs(collName, 20);

            String subpath = "multishard-test";
            String snapshotName = "backup_multishard";
            var args = makeArgs(env, "s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
            args.solrCollections = List.of(collName);

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
            creator.run();

            try (var s3 = env.testS3Client()) {
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
    }

    @Test
    void cloudBackupToSubpath_preExistingMarker_idempotentAndSucceeds() throws Exception {
        try (var env = new TestEnv()) {
            String collName = "preexist_marker_coll";
            env.createCollection(collName, 1);
            env.indexDocs(collName, 5);

            String subpath = "preexist-marker";
            String snapshotName = "backup_preexist";
            var args = makeArgs(env, "s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
            args.solrCollections = List.of(collName);

            try (var s3 = env.testS3Client()) {
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

            try (var s3 = env.testS3Client()) {
                var keys = listS3Keys(s3, subpath + "/" + snapshotName + "/");
                Assertions.assertFalse(keys.isEmpty(),
                    "Backup should succeed with pre-existing parent marker");
            }
        }
    }

    @Test
    void cloudBackup_successiveSnapshots_isolatedInS3() throws Exception {
        try (var env = new TestEnv()) {
            String collName = "successive_coll";
            env.createCollection(collName, 1);
            env.indexDocs(collName, 3);

            String subpath = "successive-test";

            var args1 = makeArgs(env, "s3://" + BUCKET_NAME + "/" + subpath, "snap_v1");
            args1.solrCollections = List.of(collName);
            var ctx1 = SnapshotTestContext.factory().noOtelTracking();
            new CreateSnapshot(args1, ctx1.createSnapshotCreateContext()).run();

            env.indexDocs(collName, 5);
            var args2 = makeArgs(env, "s3://" + BUCKET_NAME + "/" + subpath, "snap_v2");
            args2.solrCollections = List.of(collName);
            var ctx2 = SnapshotTestContext.factory().noOtelTracking();
            new CreateSnapshot(args2, ctx2.createSnapshotCreateContext()).run();

            try (var s3 = env.testS3Client()) {
                var keysV1 = listS3Keys(s3, subpath + "/snap_v1/" + collName + "/");
                var keysV2 = listS3Keys(s3, subpath + "/snap_v2/" + collName + "/");
                log.info("snap_v1 keys: {}", keysV1);
                log.info("snap_v2 keys: {}", keysV2);
                Assertions.assertFalse(keysV1.isEmpty(), "First snapshot should have data");
                Assertions.assertFalse(keysV2.isEmpty(), "Second snapshot should have data");
                Assertions.assertTrue(s3ObjectExists(s3, subpath + "/snap_v1/"),
                    "snap_v1 marker should exist");
                Assertions.assertTrue(s3ObjectExists(s3, subpath + "/snap_v2/"),
                    "snap_v2 marker should exist");
            }
        }
    }

    @Test
    void cloudBackup_s3UriWithTrailingSlash_succeeds() throws Exception {
        try (var env = new TestEnv()) {
            String collName = "trailing_slash_coll";
            env.createCollection(collName, 1);
            env.indexDocs(collName, 3);

            String subpath = "trailing-slash-test";
            String snapshotName = "backup_trailing";
            var args = makeArgs(env, "s3://" + BUCKET_NAME + "/" + subpath + "/", snapshotName);
            args.solrCollections = List.of(collName);

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
            creator.run();

            try (var s3 = env.testS3Client()) {
                var keys = listS3Keys(s3, subpath + "/");
                log.info("Keys after trailing-slash URI backup: {}", keys);
                Assertions.assertFalse(keys.isEmpty(), "Backup should succeed with trailing-slash URI");
                var backupKeys = listS3Keys(s3, subpath + "/" + snapshotName + "/" + collName + "/");
                Assertions.assertFalse(backupKeys.isEmpty(),
                    "Backup data should exist at expected path despite trailing slash in URI");
            }
        }
    }

    @Test
    void cloudBackupToSubpath_autoDiscoverCollections() throws Exception {
        try (var env = new TestEnv()) {
            String collName = "autodiscover_coll";
            env.createCollection(collName, 1);
            env.indexDocs(collName, 5);

            String subpath = "autodiscover-test";
            String snapshotName = "backup_autodiscover";
            var args = makeArgs(env, "s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
            args.solrCollections = List.of();

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
            creator.run();

            try (var s3 = env.testS3Client()) {
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
    }

    // -- S3 directory marker metadata and downstream-reader layout verification --

    @Test
    void cloudBackup_directoryMarkerHasCorrectMetadata() throws Exception {
        try (var env = new TestEnv()) {
            String collName = "marker_meta_coll";
            env.createCollection(collName, 1);
            env.indexDocs(collName, 3);

            String subpath = "marker-meta-test";
            String snapshotName = "backup_marker_meta";
            var args = makeArgs(env, "s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
            args.solrCollections = List.of(collName);

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext()).run();

            try (var s3 = env.testS3Client()) {
                HeadObjectResponse parentMarker = s3.headObject(HeadObjectRequest.builder()
                    .bucket(BUCKET_NAME).key(subpath + "/").build());
                Assertions.assertEquals("application/x-directory", parentMarker.contentType(),
                    "Parent marker must have content-type application/x-directory");
                Assertions.assertEquals(0L, parentMarker.contentLength(),
                    "Parent marker must be zero bytes");

                HeadObjectResponse snapMarker = s3.headObject(HeadObjectRequest.builder()
                    .bucket(BUCKET_NAME).key(subpath + "/" + snapshotName + "/").build());
                Assertions.assertEquals("application/x-directory", snapMarker.contentType(),
                    "Per-snapshot marker must have content-type application/x-directory");
                Assertions.assertEquals(0L, snapMarker.contentLength(),
                    "Per-snapshot marker must be zero bytes");
            }
        }
    }

    @Test
    void cloudBackup_producesExpectedLayoutForDownstreamReaders() throws Exception {
        try (var env = new TestEnv()) {
            String collName = "layout_verify_coll";
            env.createCollection(collName, 1);
            env.indexDocs(collName, 5);

            String subpath = "layout-verify";
            String snapshotName = "backup_layout";
            var args = makeArgs(env, "s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
            args.solrCollections = List.of(collName);

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext()).run();

            try (var s3 = env.testS3Client()) {
                String backupNamePrefix = subpath + "/" + snapshotName + "/" + collName + "/";
                String collDataPrefix = backupNamePrefix + collName + "/";
                var keys = listS3Keys(s3, backupNamePrefix);
                log.info("All keys under backup name '{}': {}", collName, keys);

                Assertions.assertTrue(
                    keys.stream().anyMatch(k -> k.startsWith(collDataPrefix + "zk_backup_0/")),
                    "Backup must contain zk_backup_0/ for metadata migration; keys=" + keys);
                Assertions.assertTrue(
                    keys.stream().anyMatch(k -> k.startsWith(collDataPrefix + "shard_backup_metadata/")),
                    "Backup must contain shard_backup_metadata/ for document backfill; keys=" + keys);
                Assertions.assertTrue(
                    keys.stream().anyMatch(k -> k.equals(collDataPrefix + "backup_0.properties")),
                    "Backup must contain backup_0.properties for collection discovery; keys=" + keys);
                Assertions.assertTrue(
                    keys.stream().anyMatch(k -> k.startsWith(collDataPrefix + "index/")),
                    "Backup must contain index/ directory with Lucene files; keys=" + keys);
            }
        }
    }

    @Test
    void cloudBackup_multiCollection_eachHasFullLayout() throws Exception {
        try (var env = new TestEnv()) {
            String coll1 = "layout_multi_a";
            String coll2 = "layout_multi_b";
            env.createCollection(coll1, 1);
            env.createCollection(coll2, 2);
            env.indexDocs(coll1, 4);
            env.indexDocs(coll2, 7);

            String subpath = "layout-multi";
            String snapshotName = "backup_multi_layout";
            var args = makeArgs(env, "s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
            args.solrCollections = List.of(coll1, coll2);

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext()).run();

            try (var s3 = env.testS3Client()) {
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
    }

    @Test
    void cloudBackup_bucketRoot_noParentMarkerCreated() throws Exception {
        try (var env = new TestEnv()) {
            String collName = "bucket_root_marker_coll";
            env.createCollection(collName, 1);
            env.indexDocs(collName, 3);

            String snapshotName = "backup_root_marker_check";
            var args = makeArgs(env, "s3://" + BUCKET_NAME, snapshotName);
            args.solrCollections = List.of(collName);

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext()).run();

            try (var s3 = env.testS3Client()) {
                Assertions.assertTrue(s3ObjectExists(s3, snapshotName + "/"),
                    "Per-snapshot marker should exist at bucket root");
                Assertions.assertFalse(s3ObjectExists(s3, "/"),
                    "No directory marker should be created for bucket root path '/'");

                String collDataPrefix = snapshotName + "/" + collName + "/" + collName + "/";
                var keys = listS3Keys(s3, snapshotName + "/");
                Assertions.assertTrue(
                    keys.stream().anyMatch(k -> k.startsWith(collDataPrefix + "zk_backup_0/")),
                    "Bucket root backup must contain collection data; keys=" + keys);
            }
        }
    }

    @Test
    void cloudBackup_successiveSnapshots_bothHaveFullLayout() throws Exception {
        try (var env = new TestEnv()) {
            String collName = "successive_layout_coll";
            env.createCollection(collName, 1);
            env.indexDocs(collName, 3);

            String subpath = "successive-layout";

            var args1 = makeArgs(env, "s3://" + BUCKET_NAME + "/" + subpath, "snap_first");
            args1.solrCollections = List.of(collName);
            var ctx1 = SnapshotTestContext.factory().noOtelTracking();
            new CreateSnapshot(args1, ctx1.createSnapshotCreateContext()).run();

            env.indexDocs(collName, 5);
            var args2 = makeArgs(env, "s3://" + BUCKET_NAME + "/" + subpath, "snap_second");
            args2.solrCollections = List.of(collName);
            var ctx2 = SnapshotTestContext.factory().noOtelTracking();
            new CreateSnapshot(args2, ctx2.createSnapshotCreateContext()).run();

            try (var s3 = env.testS3Client()) {
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
    }

    // -- S3 helper branch-coverage tests --

    @Test
    void cloudBackup_endpointWithoutScheme_succeeds() throws Exception {
        try (var env = new TestEnv()) {
            String collName = "noscheme_coll";
            env.createCollection(collName, 1);
            env.indexDocs(collName, 4);

            String subpath = "noscheme-endpoint";
            String snapshotName = "backup_noscheme";
            var args = makeArgs(env, "s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
            args.solrCollections = List.of(collName);
            String endpoint = env.localStackEndpoint();
            int schemeIdx = endpoint.indexOf("://");
            Assertions.assertTrue(schemeIdx > 0, "LocalStack endpoint should have a scheme");
            args.s3Endpoint = endpoint.substring(schemeIdx + 3);

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext()).run();

            try (var s3 = env.testS3Client()) {
                Assertions.assertTrue(s3ObjectExists(s3, subpath + "/"),
                    "Parent marker should exist when endpoint passed without scheme");
                Assertions.assertTrue(s3ObjectExists(s3, subpath + "/" + snapshotName + "/"),
                    "Per-snapshot marker should exist when endpoint passed without scheme");
                var keys = listS3Keys(s3, subpath + "/" + snapshotName + "/" + collName + "/");
                Assertions.assertFalse(keys.isEmpty(),
                    "Backup data should land when endpoint has no scheme; keys=" + keys);
            }
        }
    }

    @Test
    void cloudBackup_bothMarkersPreExist_idempotent() throws Exception {
        try (var env = new TestEnv()) {
            String collName = "preexist_both_coll";
            env.createCollection(collName, 1);
            env.indexDocs(collName, 2);

            String subpath = "preexist-both";
            String snapshotName = "backup_preexist_both";
            var args = makeArgs(env, "s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
            args.solrCollections = List.of(collName);

            byte[] sentinel = new byte[] { (byte) 0x42 };
            try (var s3 = env.testS3Client()) {
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

            try (var s3 = env.testS3Client()) {
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

                var keys = listS3Keys(s3, subpath + "/" + snapshotName + "/" + collName + "/");
                Assertions.assertFalse(keys.isEmpty(),
                    "Backup should succeed even when both markers already exist; keys=" + keys);
            }
        }
    }

    @Test
    void cloudBackup_backupPropertiesFileHasExpectedKeys() throws Exception {
        try (var env = new TestEnv()) {
            String collName = "props_coll";
            env.createCollection(collName, 1);
            env.indexDocs(collName, 2);

            String subpath = "props-test";
            String snapshotName = "backup_props";
            var args = makeArgs(env, "s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
            args.solrCollections = List.of(collName);

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext()).run();

            String propsKey = subpath + "/" + snapshotName + "/" + collName + "/" + collName
                + "/backup_0.properties";
            String body;
            try (var s3 = env.testS3Client();
                 var stream = s3.getObject(GetObjectRequest.builder()
                     .bucket(BUCKET_NAME).key(propsKey).build())) {
                body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            log.info("backup_0.properties contents for {}:\n{}", collName, body);

            Assertions.assertTrue(body.contains("collection=" + collName)
                    || body.contains("collection\\=" + collName),
                "backup_0.properties must declare collection=" + collName + "; body=" + body);
            Assertions.assertTrue(body.contains("indexVersion="),
                "backup_0.properties must declare indexVersion=...; body=" + body);
            Assertions.assertTrue(body.contains("backupId=") || body.contains("startTime="),
                "backup_0.properties must include backupId or startTime; body=" + body);
        }
    }

    @Test
    void cloudBackup_delimiterListingReturnsEachCollectionAsCommonPrefix() throws Exception {
        try (var env = new TestEnv()) {
            String coll1 = "delim_coll_a";
            String coll2 = "delim_coll_b";
            String coll3 = "delim_coll_c";
            env.createCollection(coll1, 1);
            env.createCollection(coll2, 1);
            env.createCollection(coll3, 2);
            env.indexDocs(coll1, 3);
            env.indexDocs(coll2, 4);
            env.indexDocs(coll3, 5);

            String subpath = "delim-listing";
            String snapshotName = "backup_delim";
            var args = makeArgs(env, "s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
            args.solrCollections = List.of(coll1, coll2, coll3);

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext()).run();

            String snapRoot = subpath + "/" + snapshotName + "/";
            try (var s3 = env.testS3Client()) {
                ListObjectsV2Response resp = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(BUCKET_NAME)
                    .prefix(snapRoot)
                    .delimiter("/")
                    .build());
                var commonPrefixes = resp.commonPrefixes().stream()
                    .map(p -> p.prefix())
                    .toList();
                log.info("Common prefixes under '{}': {}", snapRoot, commonPrefixes);

                for (String coll : List.of(coll1, coll2, coll3)) {
                    String expected = snapRoot + coll + "/";
                    Assertions.assertTrue(commonPrefixes.contains(expected),
                        "Delimiter listing must expose collection '" + coll
                            + "' as common prefix '" + expected + "'; got=" + commonPrefixes);
                }
                Assertions.assertEquals(3, commonPrefixes.size(),
                    "Snapshot root should have exactly one common prefix per collection; got="
                        + commonPrefixes);
            }
        }
    }

    @Test
    void cloudBackup_s3LocationMarkerFailure_continuesToBackup() throws Exception {
        try (var env = new TestEnv()) {
            String collName = "nonexistent_bucket_coll";
            env.createCollection(collName, 1);
            env.indexDocs(collName, 2);

            String missingBucket = "no-such-bucket-" + System.nanoTime();
            String snapshotName = "backup_missing_bucket";
            var args = makeArgs(env, "s3://" + missingBucket + "/anyprefix", snapshotName);
            args.solrCollections = List.of(collName);

            var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
            try (var logCapture = new CloseableLogSetup(SolrBackupStrategy.class.getName())) {
                var creator = new CreateSnapshot(args, snapshotContext.createSnapshotCreateContext());
                Assertions.assertThrows(Exception.class, creator::run,
                    "Backup should ultimately fail when S3 bucket does not exist");

                boolean sawWarn = logCapture.getLogEvents().stream()
                    .anyMatch(m -> m.contains("Failed to ensure S3 directory markers")
                        && m.contains(missingBucket));
                Assertions.assertTrue(sawWarn,
                    "Expected WARN log from ensureS3LocationExists for missing bucket; got events: "
                        + logCapture.getLogEvents());
            }

            try (var s3 = env.testS3Client()) {
                Assertions.assertFalse(s3ObjectExists(s3, "anyprefix/"),
                    "No marker should exist in the real test bucket at the random prefix");
            }
        }
    }

    @Test
    void buildS3Client_nullOrEmptyEndpoint_usesAwsDefaultResolution() throws Exception {
        Method build = SolrBackupStrategy.class.getDeclaredMethod(
            "buildS3Client", String.class, String.class);
        build.setAccessible(true);

        try (S3Client c = (S3Client) build.invoke(null, "us-east-1", null)) {
            Assertions.assertFalse(c.serviceClientConfiguration().endpointOverride().isPresent(),
                "null endpoint should leave endpointOverride unset");
        }
        try (S3Client c = (S3Client) build.invoke(null, "us-east-1", "")) {
            Assertions.assertFalse(c.serviceClientConfiguration().endpointOverride().isPresent(),
                "empty endpoint should leave endpointOverride unset");
        }
        try (S3Client c = (S3Client) build.invoke(null, "us-east-1", "localhost:4566")) {
            var opt = c.serviceClientConfiguration().endpointOverride();
            Assertions.assertTrue(opt.isPresent(), "endpoint should be applied when non-empty");
            Assertions.assertEquals("http://localhost:4566", opt.get().toString(),
                "endpoint without scheme must be prefixed with http://");
        }
        try (S3Client c = (S3Client) build.invoke(null, "us-east-1", "https://s3.example.com")) {
            var opt = c.serviceClientConfiguration().endpointOverride();
            Assertions.assertTrue(opt.isPresent(), "endpoint should be applied when non-empty");
            Assertions.assertEquals("https://s3.example.com", opt.get().toString(),
                "endpoint with scheme must be used verbatim");
        }
    }

    @Test
    void cloudBackup_onlySnapshotMarkerPreExists_parentCreated() throws Exception {
        try (var env = new TestEnv()) {
            String collName = "preexist_snap_only_coll";
            env.createCollection(collName, 1);
            env.indexDocs(collName, 3);

            String subpath = "preexist-snap-only";
            String snapshotName = "backup_snap_only";
            var args = makeArgs(env, "s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
            args.solrCollections = List.of(collName);

            try (var s3 = env.testS3Client()) {
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

            try (var s3 = env.testS3Client()) {
                Assertions.assertTrue(s3ObjectExists(s3, subpath + "/"),
                    "Parent marker should have been created by ensureS3LocationExists");
                var keys = listS3Keys(s3, subpath + "/" + snapshotName + "/");
                Assertions.assertFalse(keys.isEmpty(),
                    "Backup should have written data under the pre-existing snapshot marker");
            }
        }
    }

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
                throw new UnsupportedOperationException("Unexpected call: " + m.getName());
            });

        var strategy = new SolrBackupStrategy(makeArgs(null, "s3://" + BUCKET_NAME + "/ignored", "ignored"));

        InvocationTargetException ite = Assertions.assertThrows(InvocationTargetException.class,
            () -> exists.invoke(strategy, throwingClient, "some-bucket", "some/key/"));
        Assertions.assertTrue(ite.getCause() instanceof S3Exception,
            "Expected S3Exception to be rethrown for non-404 status; got: " + ite.getCause());
        Assertions.assertEquals(403, ((S3Exception) ite.getCause()).statusCode(),
            "Rethrown exception should preserve the original 403 status code");
    }

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

        var strategy = new SolrBackupStrategy(makeArgs(null, "s3://" + BUCKET_NAME + "/ignored", "ignored"));
        Object result = exists.invoke(strategy, throwingClient, "some-bucket", "some/key/");
        Assertions.assertEquals(Boolean.FALSE, result,
            "s3DirectoryMarkerExists should return false for a 404 S3Exception (not rethrow)");
    }

    @Test
    void cloudBackup_sameSnapshotNameTwice_appendsIncrementalRevisions() throws Exception {
        try (var env = new TestEnv()) {
            String collName = "incremental_coll";
            env.createCollection(collName, 1);
            env.indexDocs(collName, 3);

            String subpath = "incremental-revisions";
            String snapshotName = "backup_same_name";
            var args = makeArgs(env, "s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
            args.solrCollections = List.of(collName);

            new CreateSnapshot(args, SnapshotTestContext.factory().noOtelTracking()
                .createSnapshotCreateContext()).run();

            env.deleteSolrAsyncStatus(
                org.opensearch.migrations.bulkload.solr.SolrSnapshotCreator.asyncIdFor(
                    snapshotName, collName));

            env.indexDocs(collName, 5);

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
            try (var s3 = env.testS3Client()) {
                var keys = listS3Keys(s3, collDataPrefix);
                log.info("Keys after two snapshots to the same name: {}", keys);

                Assertions.assertTrue(
                    keys.stream().anyMatch(k -> k.startsWith(collDataPrefix + "zk_backup_0/")),
                    "zk_backup_0/ (first revision) must exist; keys=" + keys);
                Assertions.assertTrue(
                    keys.stream().anyMatch(k -> k.startsWith(collDataPrefix + "zk_backup_1/")),
                    "zk_backup_1/ (second revision, produced by same-name re-run) must exist; keys=" + keys);
                Assertions.assertTrue(
                    keys.stream().anyMatch(k -> k.equals(collDataPrefix + "backup_0.properties")),
                    "backup_0.properties must exist; keys=" + keys);
                Assertions.assertTrue(
                    keys.stream().anyMatch(k -> k.equals(collDataPrefix + "backup_1.properties")),
                    "backup_1.properties (second revision) must exist; keys=" + keys);

                var shardMetaKeys = keys.stream()
                    .filter(k -> k.contains("/shard_backup_metadata/md_shard1_"))
                    .toList();
                Assertions.assertTrue(shardMetaKeys.stream().anyMatch(k -> k.endsWith("_0.json")),
                    "md_shard1_0.json must exist after run #1; keys=" + shardMetaKeys);
                Assertions.assertTrue(shardMetaKeys.stream().anyMatch(k -> k.endsWith("_1.json")),
                    "md_shard1_1.json must exist after run #2 (same snapshot name); keys=" + shardMetaKeys);
            }
        }
    }

    @Test
    void cloudBackup_sameSnapshotNameTwice_multiShard_perShardRevisionCounters() throws Exception {
        try (var env = new TestEnv()) {
            String collName = "incremental_multishard_coll";
            env.createCollection(collName, 2);
            env.indexDocs(collName, 12);

            String subpath = "incremental-multishard";
            String snapshotName = "backup_multishard_same_name";
            var args = makeArgs(env, "s3://" + BUCKET_NAME + "/" + subpath, snapshotName);
            args.solrCollections = List.of(collName);

            new CreateSnapshot(args, SnapshotTestContext.factory().noOtelTracking()
                .createSnapshotCreateContext()).run();

            env.deleteSolrAsyncStatus(
                org.opensearch.migrations.bulkload.solr.SolrSnapshotCreator.asyncIdFor(
                    snapshotName, collName));

            env.indexDocs(collName, 8);
            new CreateSnapshot(args, SnapshotTestContext.factory().noOtelTracking()
                .createSnapshotCreateContext()).run();

            String collDataPrefix = subpath + "/" + snapshotName + "/" + collName + "/" + collName + "/";
            try (var s3 = env.testS3Client()) {
                var shardMetaKeys = listS3Keys(s3, collDataPrefix + "shard_backup_metadata/");
                log.info("Shard metadata keys after two multi-shard snapshots: {}", shardMetaKeys);

                for (String shard : List.of("shard1", "shard2")) {
                    Assertions.assertTrue(
                        shardMetaKeys.stream().anyMatch(k -> k.endsWith("/md_" + shard + "_0.json")),
                        shard + " must have rev 0 metadata; keys=" + shardMetaKeys);
                    Assertions.assertTrue(
                        shardMetaKeys.stream().anyMatch(k -> k.endsWith("/md_" + shard + "_1.json")),
                        shard + " must have rev 1 metadata (same-name re-run); keys=" + shardMetaKeys);
                }

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
}
