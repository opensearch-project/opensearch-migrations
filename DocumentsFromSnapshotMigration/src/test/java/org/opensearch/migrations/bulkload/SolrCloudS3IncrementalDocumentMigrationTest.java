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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migrates a wrapped, incremental (SIP-12) SolrCloud backup uploaded verbatim to S3 through the
 * {@code RfsMigrateDocuments} S3 path, for both Solr 8 and Solr 9.
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

    static Stream<SolrClusterContainer.SolrVersion> solrCloudVersions() {
        return Stream.of(SolrClusterContainer.SOLR_8, SolrClusterContainer.SOLR_9);
    }

    @TempDir
    Path tempDir;

    @ParameterizedTest(name = "{0}")
    @MethodSource("solrCloudVersions")
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void wrappedIncrementalSolrCloudBackupInS3MigratesAllShards(
        SolrClusterContainer.SolrVersion version
    ) throws Exception {
        // Per-version snapshot prefix so the two invocations don't collide on the shared bucket.
        var snapshotName = "solrcloud" + version.major() + "_incremental_backup";

        try (var solr = SolrClusterContainer.cloud(version)) {
            solr.start();

            createCollection(solr);
            indexDocuments(solr, DOC_COUNT);
            var localBackup = incrementalBackupAndCopyToHost(solr, snapshotName);

            // Confirm the copied tree is the wrapped incremental (UUID) layout, not the non-incremental path.
            var collectionDir = localBackup.resolve(COLLECTION);
            assertTrue(Files.isDirectory(collectionDir.resolve("index")),
                "expected an index/ dir of UUID files in the incremental backup");
            assertTrue(Files.isDirectory(collectionDir.resolve("shard_backup_metadata")),
                "expected shard_backup_metadata/ (SIP-12 incremental marker)");
            var shardMdCount = countMatchingFiles(collectionDir.resolve("shard_backup_metadata"), "md_", ".json");
            assertEquals(NUM_SHARDS, shardMdCount, "expected one shard-metadata file per shard");

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
                assertEquals(DOC_COUNT, count,
                    "all docs across all " + NUM_SHARDS + " shards should migrate from the S3 incremental backup");
            }
        }
    }

    // ---- Solr collection + docs ----

    private void createCollection(SolrClusterContainer solr) throws Exception {
        var create = solr.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=CREATE"
                + "&name=" + COLLECTION
                + "&numShards=" + NUM_SHARDS
                + "&replicationFactor=1"
                + "&maxShardsPerNode=" + NUM_SHARDS
                + "&wt=json");
        log.atInfo().setMessage("Create collection: {}").addArgument(create.getStdout()).log();
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

    // ---- Incremental BACKUP → host (preserving the <collection>/ wrapper) ----

    private Path incrementalBackupAndCopyToHost(SolrClusterContainer solr, String snapshotName) throws Exception {
        var backupLocation = "/var/solr/data/backups";
        solr.execInContainer("mkdir", "-p", backupLocation);

        // incremental=true forces the SIP-12 UUID layout; default in Solr 9, opt-in in Solr 8.x.
        var backup = solr.execInContainer("curl", "-s",
            "http://localhost:8983/solr/admin/collections?action=BACKUP"
                + "&name=" + snapshotName
                + "&collection=" + COLLECTION
                + "&location=" + backupLocation
                + "&incremental=true"
                + "&wt=json");
        log.atInfo().setMessage("Incremental BACKUP response: {}").addArgument(backup.getStdout()).log();
        if (backup.getStdout().contains("\"status\":500") || backup.getStdout().contains("\"status\":400")) {
            throw new IllegalStateException("Incremental BACKUP failed: " + backup.getStdout());
        }

        // Incremental backup is complete once backup_*.properties and md_*.json are both present.
        var backupDir = backupLocation + "/" + snapshotName;
        boolean ready = false;
        for (int i = 0; i < 60; i++) {
            var check = solr.execInContainer("sh", "-c",
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
            var listing = solr.execInContainer("sh", "-c", "ls -laR " + backupDir + " 2>&1 | head -200");
            throw new IllegalStateException(
                "Incremental SolrCloud BACKUP did not complete under " + backupDir
                    + " within 60s.\nContainer listing:\n" + listing.getStdout());
        }

        // Keep the <collection>/ wrapper so S3 holds s3://bucket/<snapshotName>/<collection>/...
        var containerCollectionDir = backupDir + "/" + COLLECTION;
        var localBackupRoot = tempDir.resolve("incremental_backup");
        var localCollectionDir = localBackupRoot.resolve(COLLECTION);
        Files.createDirectories(localCollectionDir);
        var find = solr.execInContainer("find", containerCollectionDir, "-type", "f");
        for (var line : find.getStdout().trim().split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            var rel = line.substring(containerCollectionDir.length()).replaceFirst("^/", "");
            var localFile = localCollectionDir.resolve(rel);
            Files.createDirectories(localFile.getParent());
            solr.copyFileFromContainer(line, localFile.toString());
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

    private ProcessBuilder buildMigrateProcess(
        String targetUrl, String snapshotName, SolrClusterContainer.SolrVersion version
    ) throws Exception {
        var s3LocalDir = tempDir.resolve("s3-download");
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
