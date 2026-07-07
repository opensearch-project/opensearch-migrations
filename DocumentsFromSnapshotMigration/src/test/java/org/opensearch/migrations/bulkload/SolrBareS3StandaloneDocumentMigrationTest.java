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
 * Migrates a standalone Solr backup uploaded to S3 verbatim through the {@code RfsMigrateDocuments}
 * S3 path, for Solr 7, 8, and 9. A standalone replication-handler backup is a flat
 * {@code snapshot.<name>/} Lucene index with no {@code zk_backup}/{@code backup.properties} and no
 * recorded core name, so the target index name is supplied via {@code --solr-collection-name}. The
 * {@code snapshot.<name>/} wrapper is preserved on S3, exercising the wrapped STANDALONE branch of
 * the S3 discovery (distinct from the flat-root branch).
 */
@Slf4j
@Tag("isolatedTest")
@Testcontainers
public class SolrBareS3StandaloneDocumentMigrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BUCKET_NAME = "solr-bare-s3-standalone";
    private static final String REGION = "us-east-1";
    private static final String LOCALSTACK_ALIAS = "localstack";
    private static final String CORE = "standalone_core";
    private static final String BACKUP_NAME = "standalone_backup";
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
            SolrClusterContainer.SOLR_7, SolrClusterContainer.SOLR_8, SolrClusterContainer.SOLR_9);
    }

    @TempDir
    Path tempDir;

    @ParameterizedTest(name = "{0}")
    @MethodSource("versions")
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void wrappedStandaloneInS3MigratesToOverriddenIndexName(
        SolrClusterContainer.SolrVersion version
    ) throws Exception {
        var index = "standalone_solr" + version.major();
        var snapshotName = "snapshot_" + index;

        try (var solr = new SolrClusterContainer(version)) {
            solr.start();

            createCore(solr);
            indexDocuments(solr, DOC_COUNT);
            var localBackup = backupAndCopyToHost(solr);
            uploadVerbatimToS3(localBackup, snapshotName);

            try (var target = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
                CompletableFuture.runAsync(target::start).join();

                // RFS migrates one shard per process invocation; re-run until no work is left.
                for (int attempt = 0; attempt < 3; attempt++) {
                    var result = runProcessCapturing(buildMigrateProcess(target.getUrl(), snapshotName, index, version), 300);
                    log.atInfo().setMessage("RfsMigrateDocuments attempt {} exited with {}")
                        .addArgument(attempt).addArgument(result.exitCode).log();
                    if (result.exitCode == RfsMigrateDocuments.NO_WORK_LEFT_EXIT_CODE
                        || result.exitCode == RfsMigrateDocuments.NO_WORK_AVAILABLE_EXIT_CODE) {
                        break;
                    }
                    assertEquals(0, result.exitCode, "each shard migration should succeed");
                }

                var targetOps = new ClusterOperations(target);
                targetOps.get("/" + index + "/_refresh");
                var countResp = targetOps.get("/" + index + "/_count");
                assertEquals(200, countResp.getKey(),
                    "target index '" + index + "' (from --solr-collection-name) should exist");
                var count = MAPPER.readTree(countResp.getValue()).path("count").asInt();
                assertEquals(DOC_COUNT, count, "all docs should be migrated into the overridden index name");
            }
        }
    }

    // ---- Solr backup → host ----

    private void createCore(SolrClusterContainer solr) throws Exception {
        var result = solr.execInContainer("solr", "create_core", "-c", CORE);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("Failed to create Solr core: " + result.getStderr());
        }
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
            "http://localhost:8983/solr/" + CORE + "/update?commit=true",
            "-d", sb.toString());
    }

    private Path backupAndCopyToHost(SolrClusterContainer solr) throws Exception {
        var probe = solr.execInContainer("sh", "-c",
            "for d in /var/solr/data /opt/solr/server/solr; do "
            + "  if [ -d \"$d\" ] && [ -w \"$d\" ]; then echo \"$d\"; break; fi; done");
        var solrDataDir = probe.getStdout().trim();
        if (solrDataDir.isEmpty()) {
            throw new IllegalStateException("No writable Solr data directory found in container");
        }

        solr.execInContainer("curl", "-s",
            "http://localhost:8983/solr/" + CORE
                + "/replication?command=backup&location=" + solrDataDir + "&name=" + BACKUP_NAME);

        var snapshotDirName = "snapshot." + BACKUP_NAME;
        var snapshotDir = solrDataDir + "/" + snapshotDirName;
        boolean ready = false;
        for (int i = 0; i < 60; i++) {
            var find = solr.execInContainer("sh", "-c",
                "find " + snapshotDir + " -name 'segments_*' -type f 2>/dev/null | head -1");
            if (!find.getStdout().trim().isEmpty()) {
                ready = true;
                break;
            }
            Thread.sleep(1000);
        }
        if (!ready) {
            throw new IllegalStateException("Backup did not produce a segments_* file under " + snapshotDir);
        }

        // Keep the snapshot.<name>/ wrapper so S3 holds <snapshotName>/snapshot.<name>/<flat index>.
        var localBackupRoot = tempDir.resolve("bare_backup");
        var find = solr.execInContainer("find", snapshotDir, "-type", "f");
        for (var line : find.getStdout().trim().split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            var rel = line.substring(solrDataDir.length()).replaceFirst("^/", "");
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
        String targetUrl, String snapshotName, String index, SolrClusterContainer.SolrVersion version
    ) throws Exception {
        var s3LocalDir = tempDir.resolve("s3-download-" + snapshotName);
        Files.createDirectories(s3LocalDir);

        String classpath = System.getProperty("java.class.path");
        String javaExecutable = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        var args = new ArrayList<>(List.of(
            "--snapshot-name", snapshotName,
            "--source-version", "SOLR_" + version.tag(),
            "--solr-collection-name", index,
            "--s3-repo-uri", "s3://" + BUCKET_NAME,
            "--s3-region", REGION,
            "--s3-local-dir", s3LocalDir.toString(),
            "--s3-endpoint", LOCAL_STACK.getEndpoint().toString(),
            "--target-host", targetUrl,
            "--coordinator-host", targetUrl,
            "--index-allowlist", index,
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
