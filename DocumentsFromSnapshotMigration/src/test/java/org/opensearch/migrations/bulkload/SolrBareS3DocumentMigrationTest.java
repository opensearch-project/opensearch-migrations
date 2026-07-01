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

/**
 * Issue #3147 — end-to-end proof that a bare SolrCloud 7 backup uploaded to S3 <em>verbatim</em>
 * (no manual reshape) migrates through the real {@code RfsMigrateDocuments} S3 path.
 *
 * <p>Solr 7 has no S3 backup repository, so this mirrors the real customer flow: BACKUP to local
 * disk, then upload the backup tree to S3 unchanged. The on-disk shape is the bare layout
 * ({@code <snapshotName>/{backup.properties, snapshot.shardN/, zk_backup/}}), and the target index
 * name is recovered from {@code backup.properties} — exercised here against real S3 (LocalStack),
 * which is a different code path from the filesystem discovery covered by unit tests.
 */
@Slf4j
@Tag("isolatedTest")
@Testcontainers
public class SolrBareS3DocumentMigrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BUCKET_NAME = "solr-bare-s3-test";
    private static final String REGION = "us-east-1";
    private static final String LOCALSTACK_ALIAS = "localstack";
    private static final String COLLECTION = "cloud_test";
    private static final String SNAPSHOT_NAME = "cloud_backup";
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
    static final SolrClusterContainer SOLR_CLOUD = SolrClusterContainer.cloud(SolrClusterContainer.SOLR_7);

    @BeforeAll
    static void createBucket() throws Exception {
        LOCAL_STACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_NAME);
    }

    @TempDir
    Path tempDir;

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void bareSolrCloud7BackupInS3MigratesToRecoveredIndexName() throws Exception {
        createCollection();
        indexDocuments(DOC_COUNT);
        var localBackup = backupAndCopyToHost();
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
            assertEquals(DOC_COUNT, count, "all docs should be migrated into the recovered index name");
        }
    }

    // ---- Solr backup → host ----

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

    private Path backupAndCopyToHost() throws Exception {
        var probe = SOLR_CLOUD.execInContainer("sh", "-c",
            "for d in /var/solr/data /opt/solr/server/solr; do "
            + "  if [ -d \"$d\" ] && [ -w \"$d\" ]; then echo \"$d\"; break; fi; done");
        var solrDataDir = probe.getStdout().trim();
        if (solrDataDir.isEmpty()) {
            throw new IllegalStateException("No writable Solr data directory found in container");
        }
        var backupLocation = solrDataDir + "/backups";
        SOLR_CLOUD.execInContainer("mkdir", "-p", backupLocation);

        var backup = SOLR_CLOUD.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=BACKUP"
                + "&name=" + SNAPSHOT_NAME
                + "&collection=" + COLLECTION
                + "&location=" + backupLocation
                + "&wt=json");
        if (backup.getStdout().contains("\"status\":500") || backup.getStdout().contains("\"status\":400")) {
            throw new IllegalStateException("BACKUP failed: " + backup.getStdout());
        }

        var containerBackupDir = backupLocation + "/" + SNAPSHOT_NAME;
        var localBackupRoot = tempDir.resolve("bare_backup");
        Files.createDirectories(localBackupRoot);
        var find = SOLR_CLOUD.execInContainer("find", containerBackupDir, "-type", "f");
        for (var line : find.getStdout().trim().split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            var rel = line.substring(containerBackupDir.length()).replaceFirst("^/", "");
            var localFile = localBackupRoot.resolve(rel);
            Files.createDirectories(localFile.getParent());
            SOLR_CLOUD.copyFileFromContainer(line, localFile.toString());
        }
        return localBackupRoot;
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
            "--source-version", "SOLR_" + SolrClusterContainer.SOLR_7.tag(),
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
