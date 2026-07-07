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
 * End-to-end migration of a standalone <strong>Solr 8</strong> backup whose flat Lucene index sits
 * directly at the S3 repo prefix (segments_N at the root, no {@code snapshot.<name>/} wrapper),
 * through the real {@code RfsMigrateDocuments} S3 path. This is the shape a Solr 8 standalone
 * replication backup produces: raw Lucene 8 segment files ({@code _N.*}, {@code segments_N},
 * {@code Lucene80}/{@code Lucene84} codecs) with no {@code backup.properties} — so the target index
 * name is <em>derived</em> from the {@code snapshot.<name>} prefix rather than supplied via
 * {@code --solr-collection-name}. The backup here is generated fresh, so its name is arbitrary and
 * deliberately unrelated to the Solr core name.
 *
 * <p>Complements the Solr 7 sibling {@link SolrBareS3StandaloneFlatRootDocumentMigrationTest}
 * (which pins the index name with an override) and the SolrCloud counterpart
 * {@link SolrCloudS3IncrementalDocumentMigrationTest}. Coverage this closes: the Lucene 8 flat-root
 * standalone read path plus {@link org.opensearch.migrations.SolrBackupDiscovery}'s
 * {@code detectFlatRootStandaloneInS3} → {@code S3Repo.listFilesInS3Root()} name-derivation branch,
 * exercised over S3 for a Solr 8 index.
 *
 * <p>Lucene 8 segments are read via the Lucene 9 reader's backward-codecs (Solr 8 → Lucene 9 in
 * {@code SolrBackupSource.newLuceneReader}).
 */
@Slf4j
@Tag("isolatedTest")
@Testcontainers
public class SolrBareS3StandaloneFlatRootSolr8DocumentMigrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BUCKET_NAME = "solr-bare-s3-flatroot-solr8";
    private static final String REGION = "us-east-1";
    private static final String LOCALSTACK_ALIAS = "localstack";
    private static final String CORE = "standalone_core";
    private static final String BACKUP_NAME = "standalone_backup";
    // The flat index is uploaded directly under this snapshot prefix (no snapshot.<name>/ wrapper).
    // The prefix is deliberately unrelated to the Solr CORE name so the assertion below proves the
    // target index name is derived from the snapshot.<name> prefix, not from anything in the index.
    private static final String SNAPSHOT_NAME = "snapshot.solr8_flatroot_target";
    // With no backup.properties and no --solr-collection-name override, the target index name is
    // derived by stripping the "snapshot." prefix from SNAPSHOT_NAME.
    private static final String DERIVED_INDEX = "solr8_flatroot_target";
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
    static final SolrClusterContainer SOLR = new SolrClusterContainer(SolrClusterContainer.SOLR_8);

    @BeforeAll
    static void createBucket() throws Exception {
        LOCAL_STACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_NAME);
    }

    @TempDir
    Path tempDir;

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void bareStandaloneSolr8FlatRootInS3MigratesToDerivedIndexName() throws Exception {
        createCore();
        indexDocuments(DOC_COUNT);
        var localIndex = backupAndCopyIndexToHost();

        // Guard: the copied tree must be a flat Lucene index (segments_N at the root, no wrapper dir)
        // so this test really exercises the flat-root standalone path, not a wrapped layout.
        assertTrue(hasFlatSegmentsFile(localIndex),
            "expected a segments_N file directly at the index root (flat-root standalone layout)");

        uploadFlatIndexToS3(localIndex, SNAPSHOT_NAME);

        try (var target = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            CompletableFuture.runAsync(target::start).join();

            // RFS migrates one shard per process invocation; re-run until no work is left.
            for (int attempt = 0; attempt < 3; attempt++) {
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
            targetOps.get("/" + DERIVED_INDEX + "/_refresh");
            var countResp = targetOps.get("/" + DERIVED_INDEX + "/_count");
            assertEquals(200, countResp.getKey(),
                "target index '" + DERIVED_INDEX + "' (derived from the snapshot.<name> prefix) should exist");
            var count = MAPPER.readTree(countResp.getValue()).path("count").asInt();
            assertEquals(DOC_COUNT, count, "all docs should be migrated into the derived index name");
        }
    }

    // ---- Solr backup → host (only the flat index contents, not the snapshot.<name>/ dir) ----

    private void createCore() throws Exception {
        var result = SOLR.execInContainer("solr", "create_core", "-c", CORE);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("Failed to create Solr core: " + result.getStderr());
        }
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
        SOLR.execInContainer("curl", "-s", "-H", "Content-Type: application/json",
            "http://localhost:8983/solr/" + CORE + "/update?commit=true",
            "-d", sb.toString());
    }

    private Path backupAndCopyIndexToHost() throws Exception {
        var probe = SOLR.execInContainer("sh", "-c",
            "for d in /var/solr/data /opt/solr/server/solr; do "
            + "  if [ -d \"$d\" ] && [ -w \"$d\" ]; then echo \"$d\"; break; fi; done");
        var solrDataDir = probe.getStdout().trim();
        if (solrDataDir.isEmpty()) {
            throw new IllegalStateException("No writable Solr data directory found in container");
        }

        SOLR.execInContainer("curl", "-s",
            "http://localhost:8983/solr/" + CORE
                + "/replication?command=backup&location=" + solrDataDir + "&name=" + BACKUP_NAME);

        var snapshotDir = solrDataDir + "/snapshot." + BACKUP_NAME;
        boolean ready = false;
        for (int i = 0; i < 60; i++) {
            var find = SOLR.execInContainer("sh", "-c",
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

        // Copy the flat index files to the host WITHOUT the snapshot.<name>/ wrapper, so the S3
        // upload places segments_N directly at the snapshot root (the flat-root layout).
        var localIndexRoot = tempDir.resolve("flat_index");
        Files.createDirectories(localIndexRoot);
        var find = SOLR.execInContainer("find", snapshotDir, "-type", "f");
        for (var line : find.getStdout().trim().split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            var fileName = line.substring(line.lastIndexOf('/') + 1);
            SOLR.copyFileFromContainer(line, localIndexRoot.resolve(fileName).toString());
        }
        return localIndexRoot;
    }

    private static boolean hasFlatSegmentsFile(Path indexRoot) throws IOException {
        try (var files = Files.list(indexRoot)) {
            return files.anyMatch(p -> p.getFileName().toString().startsWith("segments_"));
        }
    }

    // ---- host → S3 (flat index directly under the snapshot prefix) ----

    private void uploadFlatIndexToS3(Path localIndexRoot, String snapshotName) throws Exception {
        var containerPath = "/tmp/" + snapshotName;
        LOCAL_STACK.copyFileToContainer(
            MountableFile.forHostPath(localIndexRoot.toString()), containerPath);
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
            "--index-allowlist", DERIVED_INDEX,
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
