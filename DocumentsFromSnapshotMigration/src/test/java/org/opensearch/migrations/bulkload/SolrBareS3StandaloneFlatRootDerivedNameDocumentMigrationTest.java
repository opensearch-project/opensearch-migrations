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
import org.junit.jupiter.params.provider.Arguments;
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
 * Migrates a standalone Solr 6/8/9 backup whose flat Lucene index sits directly at the S3 repo prefix
 * (segments_N at the root, no {@code snapshot.<name>/} wrapper) through the {@code RfsMigrateDocuments}
 * S3 path. With no {@code backup.properties} and no {@code --solr-collection-name} override, the target
 * index name is derived from the {@code snapshot.<name>} prefix. Covers both multi-segment and
 * force-merged single-segment indexes.
 */
@Slf4j
@Tag("isolatedTest")
@Testcontainers
public class SolrBareS3StandaloneFlatRootDerivedNameDocumentMigrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BUCKET_NAME = "solr-bare-s3-flatroot-derived";
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

    // One commit per batch: multiple batches yield multiple segments, a single batch yields one.
    static Stream<Arguments> cases() {
        return Stream.of(
            Arguments.of(SolrClusterContainer.SOLR_6, 4),
            Arguments.of(SolrClusterContainer.SOLR_6, 1),
            Arguments.of(SolrClusterContainer.SOLR_8, 4),
            Arguments.of(SolrClusterContainer.SOLR_8, 1),
            Arguments.of(SolrClusterContainer.SOLR_9, 4),
            Arguments.of(SolrClusterContainer.SOLR_9, 1)
        );
    }

    @TempDir
    Path tempDir;

    @ParameterizedTest(name = "{0} batches={1}")
    @MethodSource("cases")
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void flatRootStandaloneInS3MigratesToDerivedIndexName(
        SolrClusterContainer.SolrVersion version, int batches
    ) throws Exception {
        var singleSegment = batches == 1;
        // Prefix is unrelated to CORE, so a matching derived index name can only come from the prefix.
        var derivedIndex = "solr" + version.major() + (singleSegment ? "_onesegment" : "_multiseg") + "_flatroot";
        var snapshotName = "snapshot." + derivedIndex;

        try (var solr = new SolrClusterContainer(version)) {
            solr.start();

            createCore(solr);
            indexDocuments(solr, DOC_COUNT, batches);
            var localIndex = backupAndCopyIndexToHost(solr);

            var segmentCount = countSegments(localIndex);
            assertTrue(hasFlatSegmentsFile(localIndex), "expected a segments_N file at the flat index root");
            if (singleSegment) {
                assertEquals(1, segmentCount, "a single indexing batch should leave one segment");
            } else {
                assertTrue(segmentCount >= 2, "multiple indexing batches should leave multiple segments");
            }

            uploadFlatIndexToS3(localIndex, snapshotName);

            try (var target = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
                CompletableFuture.runAsync(target::start).join();

                // RFS migrates one shard per process invocation; re-run until no work is left.
                for (int attempt = 0; attempt < 3; attempt++) {
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
                targetOps.get("/" + derivedIndex + "/_refresh");
                var countResp = targetOps.get("/" + derivedIndex + "/_count");
                assertEquals(200, countResp.getKey(), "target index '" + derivedIndex + "' should exist");
                var count = MAPPER.readTree(countResp.getValue()).path("count").asInt();
                assertEquals(DOC_COUNT, count, "all docs should migrate into the derived index name");
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

    private void indexDocuments(SolrClusterContainer solr, int count, int batches) throws Exception {
        var perBatch = (int) Math.ceil((double) count / batches);
        for (int start = 1; start <= count; start += perBatch) {
            var end = Math.min(start + perBatch - 1, count);
            var sb = new StringBuilder("[");
            for (int i = start; i <= end; i++) {
                if (i > start) {
                    sb.append(",");
                }
                sb.append("{\"id\":\"doc").append(i).append("\",\"title_s\":\"Document ").append(i).append("\"}");
            }
            sb.append("]");
            // commit each batch so it flushes a new segment
            solr.execInContainer("curl", "-s", "-H", "Content-Type: application/json",
                "http://localhost:8983/solr/" + CORE + "/update?commit=true",
                "-d", sb.toString());
        }
    }

    private Path backupAndCopyIndexToHost(SolrClusterContainer solr) throws Exception {
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

        var snapshotDir = solrDataDir + "/snapshot." + BACKUP_NAME;
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

        // Copy without the snapshot.<name>/ wrapper so segments_N land at the S3 snapshot root.
        var localIndexRoot = tempDir.resolve("flat_index_" + System.identityHashCode(solr));
        Files.createDirectories(localIndexRoot);
        var find = solr.execInContainer("find", snapshotDir, "-type", "f");
        for (var line : find.getStdout().trim().split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            var fileName = line.substring(line.lastIndexOf('/') + 1);
            solr.copyFileFromContainer(line, localIndexRoot.resolve(fileName).toString());
        }
        return localIndexRoot;
    }

    private static boolean hasFlatSegmentsFile(Path indexRoot) throws IOException {
        try (var files = Files.list(indexRoot)) {
            return files.anyMatch(p -> p.getFileName().toString().startsWith("segments_"));
        }
    }

    private static int countSegments(Path indexRoot) throws IOException {
        try (var files = Files.list(indexRoot)) {
            return (int) files.filter(p -> p.getFileName().toString().endsWith(".si")).count();
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
            "--index-allowlist", snapshotName.replaceFirst("^snapshot\\.", ""),
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
