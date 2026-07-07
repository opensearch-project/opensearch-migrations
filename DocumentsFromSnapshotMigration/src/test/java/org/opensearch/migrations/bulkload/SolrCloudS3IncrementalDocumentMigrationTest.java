package org.opensearch.migrations.bulkload;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.RfsMigrateDocuments;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.solr.framework.SolrClusterContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #3147 — end-to-end proof that a <em>wrapped, incremental (SIP-12)</em> SolrCloud 8 backup
 * uploaded to S3 verbatim migrates through the real {@code RfsMigrateDocuments} S3 path.
 *
 * <p>This is the layout produced by SolrCloud 8.9+ when {@code BACKUP} is invoked with
 * {@code incremental=true} (the backup here is generated fresh; its name is arbitrary):
 * <pre>
 *   &lt;snapshotName&gt;/&lt;collection&gt;/
 *     backup_0.properties           (indexVersion=8.11.x)
 *     index/&lt;UUID files&gt;            (content-addressed Lucene files, no segments_N name)
 *     shard_backup_metadata/md_shardN_0.json   (logical Lucene name → UUID mapping, one per shard)
 *     zk_backup_0/configs/&lt;name&gt;/schema.xml
 * </pre>
 *
 * <p>Coverage gap this closes: {@link org.opensearch.migrations.bulkload.solr.SolrBackupSource}'s
 * UUID-mapped read path ({@code readLuceneIndexMapped} + {@code MappedDirectory}) and
 * {@link org.opensearch.migrations.SolrBackupDiscovery}'s per-UUID lazy S3 download
 * ({@code prepareShard}) were exercised only from a local directory (see
 * {@code SolrSnapshotToOpenSearchTest}) or, over S3, only for <em>bare non-incremental</em> layouts
 * (see {@code SolrBareS3DocumentMigrationTest}). Nothing drove the wrapped incremental layout
 * through the S3 CLI path until now.
 *
 * <p>Lucene 8.11 segments are read via the Lucene 9 reader's backward-codecs (Solr 8 → Lucene 9
 * in {@code SolrBackupSource.newLuceneReader}).
 */
@Slf4j
@Tag("isolatedTest")
@Testcontainers
public class SolrCloudS3IncrementalDocumentMigrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BUCKET_NAME = "solr-cloud-s3-incremental-test";
    private static final String REGION = "us-east-1";
    private static final String LOCALSTACK_ALIAS = "localstack";
    private static final String COLLECTION = "movies";
    // Arbitrary generated-backup name; for a wrapped SolrCloud backup the target index is the
    // collection name (COLLECTION), not the snapshot name, so this string carries no meaning.
    private static final String SNAPSHOT_NAME = "solrcloud8_incremental_backup";
    private static final int NUM_SHARDS = 2;
    private static final int DOC_COUNT = 20;

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
        .withNetworkAliases(LOCALSTACK_ALIAS);

    @Container
    static final SolrClusterContainer SOLR_CLOUD = SolrClusterContainer.cloud(SolrClusterContainer.SOLR_8);

    @BeforeAll
    static void createBucket() throws Exception {
        LOCAL_STACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_NAME);
    }

    @TempDir
    Path tempDir;

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void wrappedIncrementalSolrCloud8BackupInS3MigratesAllShards() throws Exception {
        createCollection();
        indexDocuments(DOC_COUNT);
        var localBackup = incrementalBackupAndCopyToHost();

        // Guard: assert the copied tree really is the wrapped incremental (UUID) layout — otherwise
        // this test would silently degrade into re-covering the non-incremental path.
        var collectionDir = localBackup.resolve(COLLECTION);
        assertTrue(Files.isDirectory(collectionDir.resolve("index")),
            "expected an index/ dir of UUID files in the incremental backup");
        assertTrue(Files.isDirectory(collectionDir.resolve("shard_backup_metadata")),
            "expected shard_backup_metadata/ (SIP-12 incremental marker)");
        var shardMdCount = countMatchingFiles(collectionDir.resolve("shard_backup_metadata"), "md_", ".json");
        assertEquals(NUM_SHARDS, shardMdCount, "expected one shard-metadata file per shard");

        uploadVerbatimToS3(localBackup, SNAPSHOT_NAME);

        try (var target = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            CompletableFuture.runAsync(target::start).join();

            // RFS migrates one shard per process invocation; re-run until no work is left.
            for (int attempt = 0; attempt < NUM_SHARDS + 2; attempt++) {
                var result = runProcessCapturing(buildMigrateProcess(target.getUrl()), 300);
                log.atInfo().setMessage("RfsMigrateDocuments attempt {} exited with {}")
                    .addArgument(attempt).addArgument(result.exitCode).log();
                if (result.exitCode == RfsMigrateDocuments.NO_WORK_LEFT_EXIT_CODE
                    || result.exitCode == RfsMigrateDocuments.NO_WORK_AVAILABLE_EXIT_CODE) {
                    break;
                }
                assertEquals(0, result.exitCode, "each shard migration should succeed");
            }

            var targetOps = new ClusterOperations(target);
            targetOps.get("/" + COLLECTION + "/_refresh");
            var countResp = targetOps.get("/" + COLLECTION + "/_count");
            assertEquals(200, countResp.getKey(), "target index '" + COLLECTION + "' should exist");
            var count = MAPPER.readTree(countResp.getValue()).path("count").asInt();
            assertEquals(DOC_COUNT, count,
                "all docs across all " + NUM_SHARDS + " shards should migrate from the S3 incremental backup");
        }
    }

    // ---- Solr collection + docs ----

    private void createCollection() throws Exception {
        var create = SOLR_CLOUD.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=CREATE"
                + "&name=" + COLLECTION
                + "&numShards=" + NUM_SHARDS
                + "&replicationFactor=1"
                + "&maxShardsPerNode=" + NUM_SHARDS
                + "&wt=json");
        log.atInfo().setMessage("Create collection: {}").addArgument(create.getStdout()).log();
        waitForCollectionActive();
    }

    private void waitForCollectionActive() throws Exception {
        for (int i = 0; i < 60; i++) {
            var status = SOLR_CLOUD.execInContainer("curl", "-s",
                "http://localhost:8983/solr/admin/collections?action=CLUSTERSTATUS&collection="
                    + COLLECTION + "&wt=json");
            var body = status.getStdout();
            int active = 0;
            for (int idx = 0; (idx = body.indexOf("\"state\":\"active\"", idx)) != -1; idx++) {
                active++;
            }
            if (active >= NUM_SHARDS) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Collection " + COLLECTION + " did not become active");
    }

    private void indexDocuments(int count) throws Exception {
        var sb = new StringBuilder("[");
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                sb.append(",");
            }
            sb.append("{\"id\":\"doc").append(i).append("\",\"title_s\":\"Document ").append(i).append("\"}");
        }
        sb.append("]");
        SOLR_CLOUD.execInContainer("curl", "-s", "-H", "Content-Type: application/json",
            "http://localhost:8983/solr/" + COLLECTION + "/update?commit=true",
            "-d", sb.toString());
    }

    // ---- Incremental BACKUP → host (preserving the <collection>/ wrapper) ----

    private Path incrementalBackupAndCopyToHost() throws Exception {
        var backupLocation = "/var/solr/data/backups";
        SOLR_CLOUD.execInContainer("mkdir", "-p", backupLocation);

        // incremental=true forces the SIP-12 UUID layout (index/<UUID> + shard_backup_metadata/)
        // rather than Solr 8's non-incremental default.
        var backup = SOLR_CLOUD.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=BACKUP"
                + "&name=" + SNAPSHOT_NAME
                + "&collection=" + COLLECTION
                + "&location=" + backupLocation
                + "&incremental=true"
                + "&wt=json");
        log.atInfo().setMessage("Incremental BACKUP response: {}").addArgument(backup.getStdout()).log();
        if (backup.getStdout().contains("\"status\":500") || backup.getStdout().contains("\"status\":400")) {
            throw new IllegalStateException("Incremental BACKUP failed: " + backup.getStdout());
        }

        // Poll on the incremental completion signal: backup_*.properties + md_*.json both present
        // (the pair guards against triggering on a partial write). No segments_* file is written.
        var backupDir = backupLocation + "/" + SNAPSHOT_NAME;
        boolean ready = false;
        for (int i = 0; i < 60; i++) {
            var check = SOLR_CLOUD.execInContainer("sh", "-c",
                "if find \"" + backupDir + "\" -name 'backup_*.properties' -type f 2>/dev/null | grep -q . "
                + "  && find \"" + backupDir + "\" -name 'md_*.json' -type f 2>/dev/null | grep -q .; "
                + "then echo OK; fi");
            if (check.getStdout().trim().equals("OK")) {
                ready = true;
                break;
            }
            Thread.sleep(1000);
        }
        if (!ready) {
            var listing = SOLR_CLOUD.execInContainer("sh", "-c", "ls -laR " + backupDir + " 2>&1 | head -200");
            throw new IllegalStateException(
                "Incremental SolrCloud BACKUP did not complete under " + backupDir
                    + " within 60s.\nContainer listing:\n" + listing.getStdout());
        }

        // Copy <backupDir>/<collection>/... to host, preserving the <collection>/ wrapper directory
        // so the on-S3 shape is s3://bucket/<snapshotName>/<collection>/... (the wrapped layout).
        var containerCollectionDir = backupDir + "/" + COLLECTION;
        var localBackupRoot = tempDir.resolve("incremental_backup");
        var localCollectionDir = localBackupRoot.resolve(COLLECTION);
        Files.createDirectories(localCollectionDir);
        var find = SOLR_CLOUD.execInContainer("find", containerCollectionDir, "-type", "f");
        for (var line : find.getStdout().trim().split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            var rel = line.substring(containerCollectionDir.length()).replaceFirst("^/", "");
            var localFile = localCollectionDir.resolve(rel);
            Files.createDirectories(localFile.getParent());
            SOLR_CLOUD.copyFileFromContainer(line, localFile.toString());
        }
        return localBackupRoot;
    }

    private static int countMatchingFiles(Path dir, String prefix, String suffix) throws IOException {
        try (var files = Files.list(dir)) {
            return (int) files
                .map(p -> p.getFileName().toString())
                .filter(n -> n.startsWith(prefix) && n.endsWith(suffix))
                .count();
        }
    }

    // ---- host → S3 (verbatim, no reshape) ----

    private void uploadVerbatimToS3(Path localBackupRoot, String snapshotName) throws Exception {
        var containerPath = "/tmp/" + snapshotName;
        LOCAL_STACK.copyFileToContainer(
            MountableFile.forHostPath(localBackupRoot.toString()), containerPath);
        LOCAL_STACK.execInContainer("awslocal", "s3", "cp", "--recursive",
            containerPath, "s3://" + BUCKET_NAME + "/" + snapshotName + "/");
    }

    // ---- RfsMigrateDocuments subprocess ----

    private ProcessBuilder buildMigrateProcess(String targetUrl) throws Exception {
        var s3LocalDir = tempDir.resolve("s3-download");
        Files.createDirectories(s3LocalDir);

        String classpath = System.getProperty("java.class.path");
        String javaExecutable = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        var args = new ArrayList<>(List.of(
            "--snapshot-name", SNAPSHOT_NAME,
            "--source-version", "SOLR_" + SolrClusterContainer.SOLR_8.tag(),
            "--s3-repo-uri", "s3://" + BUCKET_NAME,
            "--s3-region", REGION,
            "--s3-local-dir", s3LocalDir.toString(),
            "--s3-endpoint", LOCAL_STACK.getEndpoint().toString(),
            "--target-host", targetUrl,
            "--coordinator-host", targetUrl,
            "--index-allowlist", COLLECTION,
            "--documents-per-bulk-request", "10",
            "--max-connections", "1",
            "--initial-lease-duration", "PT10M"
        ));
        var processBuilder = new ProcessBuilder(
            javaExecutable, "-cp", classpath, "org.opensearch.migrations.RfsMigrateDocuments");
        processBuilder.command().addAll(args);
        processBuilder.environment().put("AWS_ACCESS_KEY_ID", "test");
        processBuilder.environment().put("AWS_SECRET_ACCESS_KEY", "test");
        processBuilder.environment().put("AWS_REGION", REGION);
        return processBuilder;
    }

    private static ProcessResult runProcessCapturing(ProcessBuilder processBuilder, int timeoutSeconds)
        throws Exception {
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE);
        var process = processBuilder.start();
        var output = new StringBuilder();
        var readerThread = new Thread(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append(System.lineSeparator());
                    }
                    log.atInfo().setMessage("from sub-process: {}").addArgument(line).log();
                }
            } catch (IOException e) {
                log.atWarn().setCause(e).setMessage("Couldn't read sub-process output").log();
            }
        });
        readerThread.start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("RfsMigrateDocuments did not finish within " + timeoutSeconds + "s");
        }
        readerThread.join(TimeUnit.SECONDS.toMillis(10));
        synchronized (output) {
            return new ProcessResult(process.exitValue(), output.toString());
        }
    }

    private static class ProcessResult {
        final int exitCode;
        final String output;

        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
