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
import java.util.stream.Stream;

import org.opensearch.migrations.RfsMigrateDocuments;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.http.ClusterOperations;
import org.opensearch.migrations.bulkload.solr.framework.SolrClusterContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Migrates a bare, non-incremental SolrCloud backup uploaded to S3 verbatim through the
 * {@code RfsMigrateDocuments} S3 path, for Solr 6, 7, 8, and 9. The non-incremental backup writes
 * {@code backup.properties} + {@code snapshot.shardN/} + {@code zk_backup/} directly under the
 * backup name (no collection wrapper), so the target index name is recovered from
 * {@code backup.properties} rather than supplied via an override. It is the default in Solr 6/7/8
 * and requires {@code incremental=false} in Solr 9.
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

    @BeforeAll
    static void createBucket() throws Exception {
        LOCAL_STACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_NAME);
    }

    static Stream<SolrClusterContainer.SolrVersion> versions() {
        return Stream.of(
            SolrClusterContainer.SOLR_6, SolrClusterContainer.SOLR_7,
            SolrClusterContainer.SOLR_8, SolrClusterContainer.SOLR_9);
    }

    @TempDir
    Path tempDir;

    @ParameterizedTest(name = "{0}")
    @MethodSource("versions")
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void bareSolrCloudBackupInS3MigratesToRecoveredIndexName(
        SolrClusterContainer.SolrVersion version
    ) throws Exception {
        var snapshotName = "cloud_backup_solr" + version.major();

        try (var solr = SolrClusterContainer.cloud(version)) {
            solr.start();

            createCollection(solr, version);
            indexDocuments(solr, DOC_COUNT);
            var localBackup = backupAndCopyToHost(solr, snapshotName, version);
            uploadVerbatimToS3(localBackup, snapshotName);

            try (var target = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
                CompletableFuture.runAsync(target::start).join();

                // RFS migrates one shard per process invocation; re-run until no work is left.
                for (int attempt = 0; attempt < NUM_SHARDS + 2; attempt++) {
                    var result = runProcessCapturing(buildMigrateProcess(target.getUrl(), snapshotName, version), 300);
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
    }

    // ---- Solr backup → host ----

    private void createCollection(SolrClusterContainer solr, SolrClusterContainer.SolrVersion version) throws Exception {
        if (version.major() <= 6) {
            // Solr 6 cloud has no auto-provisioned _default configset, so the Collections API CREATE
            // has no config to use; `solr create` uploads a built-in configset as part of creation.
            var create = solr.execInContainer("solr", "create", "-c", COLLECTION,
                "-shards", String.valueOf(NUM_SHARDS), "-replicationFactor", "1");
            log.atInfo().setMessage("solr create: {}").addArgument(create.getStdout()).log();
            if (create.getExitCode() != 0) {
                throw new IllegalStateException("solr create failed: " + create.getStderr());
            }
        } else {
            var create = solr.execInContainer("curl", "-s",
                "http://localhost:8983/solr/admin/collections?action=CREATE"
                    + "&name=" + COLLECTION
                    + "&numShards=" + NUM_SHARDS
                    + "&replicationFactor=1"
                    + "&maxShardsPerNode=" + NUM_SHARDS
                    + "&wt=json");
            log.atInfo().setMessage("Create collection: {}").addArgument(create.getStdout()).log();
        }
        waitForCollectionActive(solr);
    }

    private void waitForCollectionActive(SolrClusterContainer solr) throws Exception {
        for (int i = 0; i < 60; i++) {
            var status = solr.execInContainer("curl", "-s",
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

    private void indexDocuments(SolrClusterContainer solr, int count) throws Exception {
        var sb = new StringBuilder("[");
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                sb.append(",");
            }
            sb.append("{\"id\":\"doc").append(i).append("\",\"title_s\":\"Document ").append(i).append("\"}");
        }
        sb.append("]");
        solr.execInContainer("curl", "-s", "-H", "Content-Type: application/json",
            "http://localhost:8983/solr/" + COLLECTION + "/update?commit=true",
            "-d", sb.toString());
    }

    private Path backupAndCopyToHost(
        SolrClusterContainer solr, String snapshotName, SolrClusterContainer.SolrVersion version
    ) throws Exception {
        var probe = solr.execInContainer("sh", "-c",
            "for d in /var/solr/data /opt/solr/server/solr; do "
            + "  if [ -d \"$d\" ] && [ -w \"$d\" ]; then echo \"$d\"; break; fi; done");
        var solrDataDir = probe.getStdout().trim();
        if (solrDataDir.isEmpty()) {
            throw new IllegalStateException("No writable Solr data directory found in container");
        }
        var backupLocation = solrDataDir + "/backups";
        solr.execInContainer("mkdir", "-p", backupLocation);

        // Solr 9 defaults to incremental; force the bare non-incremental layout. Solr 7 predates the flag.
        var incrementalParam = version.major() >= 8 ? "&incremental=false" : "";
        var backup = solr.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=BACKUP"
                + "&name=" + snapshotName
                + "&collection=" + COLLECTION
                + "&location=" + backupLocation
                + incrementalParam
                + "&wt=json");
        if (backup.getStdout().contains("\"status\":500") || backup.getStdout().contains("\"status\":400")) {
            throw new IllegalStateException("BACKUP failed: " + backup.getStdout());
        }

        var containerBackupDir = backupLocation + "/" + snapshotName;
        var localBackupRoot = tempDir.resolve("bare_backup");
        Files.createDirectories(localBackupRoot);
        var find = solr.execInContainer("find", containerBackupDir, "-type", "f");
        for (var line : find.getStdout().trim().split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            var rel = line.substring(containerBackupDir.length()).replaceFirst("^/", "");
            var localFile = localBackupRoot.resolve(rel);
            Files.createDirectories(localFile.getParent());
            solr.copyFileFromContainer(line, localFile.toString());
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

    private ProcessBuilder buildMigrateProcess(
        String targetUrl, String snapshotName, SolrClusterContainer.SolrVersion version
    ) throws Exception {
        var s3LocalDir = tempDir.resolve("s3-download-" + snapshotName);
        Files.createDirectories(s3LocalDir);

        String classpath = System.getProperty("java.class.path");
        String javaExecutable = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        var args = new ArrayList<>(List.of(
            "--snapshot-name", snapshotName,
            "--source-version", "SOLR_" + version.tag(),
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
