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

import org.opensearch.migrations.CreateSnapshot;
import org.opensearch.migrations.RfsMigrateDocuments;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.solr.framework.SolrClusterContainer;
import org.opensearch.migrations.snapshot.creation.tracing.SnapshotTestContext;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
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

/**
 * When a Solr migration cannot read what it needs from a Solr snapshot stored on S3, the worker
 * must surface a non-retriable snapshot read failure (labeled ERROR log
 * with the snapshot path + cause) and exit with the dedicated
 * {@link RfsMigrateDocuments#SNAPSHOT_READ_FAILED_EXIT_CODE}, rather than dying with a generic exit
 * code and an unlabeled stack trace.
 *
 * <p>Two distinct failure modes are exercised, both producing the same labeled exit:
 * <ul>
 *   <li><b>Missing directories</b> — the Lucene {@code index/} and {@code shard_backup_metadata/}
 *       are absent, so shard discovery finds no segments
 *       ({@code SolrBackupReadException}).</li>
 *   <li><b>Missing files</b> — {@code shard_backup_metadata/} is present (so the reader resolves the
 *       expected per-shard index files) but the {@code index/} objects are gone, so downloading a
 *       referenced file 404s ({@code S3Repo.CouldNotReadFromS3}).</li>
 * </ul>
 *
 * <p>Tagged {@code isolatedTest} because it spins up three containers (LocalStack, SolrCloud,
 * OpenSearch) plus a subprocess and runs for ~60s+. The two cases share the static LocalStack and
 * SolrCloud containers; deletions are scoped to each test's own snapshot prefix so they don't
 * interfere.
 */
@Slf4j
@Tag("isolatedTest")
@Testcontainers
public class SolrS3SnapshotReadFailureProcessTest {

    private static final String BUCKET_NAME = "solr-doc-s3-test";
    private static final String REGION = "us-east-1";
    private static final String LOCALSTACK_ALIAS = "localstack";
    private static final String SUBPATH = "v1";

    static {
        // LocalStack accepts any non-empty credentials, but CreateSnapshot (run in-process here)
        // uses DefaultCredentialsProvider, and CI runners have no ambient creds. Seed sysprops
        // before the SDK's credential chain is first consulted.
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
    static final SolrClusterContainer SOLR_CLOUD = SolrClusterContainer.cloudWithS3Backup(
        SolrClusterContainer.SOLR_8, NETWORK, BUCKET_NAME, REGION, LOCALSTACK_ALIAS);

    @BeforeAll
    static void createBucket() throws Exception {
        LOCAL_STACK.execInContainer("awslocal", "s3", "mb", "s3://" + BUCKET_NAME);
        log.info("Created S3 bucket '{}' in LocalStack", BUCKET_NAME);
    }

    @TempDir
    Path tempDir;

    /**
     * Missing directories: deleting both {@code index/} and {@code shard_backup_metadata/} forces the
     * reader down the directory-discovery path, which finds no Lucene segments and throws a
     * {@code SolrBackupReadException}.
     */
    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void missingExpectedDirectoriesExitsWithDedicatedCode() throws Exception {
        runSolrMigrationExpectingReadFailure(
            "s3_coll_dirs", "doc_s3_snap_dirs",
            "*/index/*", "*/shard_backup_metadata/*");
    }

    /**
     * Missing files: keeping {@code shard_backup_metadata/} means the reader resolves the expected
     * per-shard index files and tries to download them; with {@code index/} deleted, that download
     * 404s and surfaces as {@code S3Repo.CouldNotReadFromS3}.
     */
    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void missingIndexFilesOnS3ExitsWithDedicatedCode() throws Exception {
        runSolrMigrationExpectingReadFailure(
            "s3_coll_files", "doc_s3_snap_files",
            "*/index/*");
    }

    /**
     * Build a valid Solr S3 backup, delete the given object globs from that snapshot's prefix, then
     * run the real {@code RfsMigrateDocuments} subprocess and assert it surfaces a labeled snapshot
     * read failure and exits with {@link RfsMigrateDocuments#SNAPSHOT_READ_FAILED_EXIT_CODE}.
     */
    private void runSolrMigrationExpectingReadFailure(
        String collName, String snapshotName, String... deleteIncludes
    ) throws Exception {
        createCollectionWithSchema(collName);
        indexOneDoc(collName);

        String s3RepoUri = "s3://" + BUCKET_NAME + "/" + SUBPATH;

        // 1. Produce a real Solr S3BackupRepository layout in LocalStack.
        var createArgs = new CreateSnapshot.Args();
        createArgs.sourceArgs.host = solrUrl();
        createArgs.sourceArgs.insecure = true;
        createArgs.sourceType = "solr";
        createArgs.snapshotName = snapshotName;
        createArgs.snapshotRepoName = "s3";
        createArgs.repoUri = s3RepoUri;
        createArgs.s3Region = REGION;
        createArgs.endpoint = localStackEndpoint();
        createArgs.solrCollections = List.of(collName);
        createArgs.noWait = false;

        var snapshotContext = SnapshotTestContext.factory().noOtelTracking();
        new CreateSnapshot(createArgs, snapshotContext.createSnapshotCreateContext()).run();
        log.info("CreateSnapshot finished — S3 at {}/{}", s3RepoUri, snapshotName);

        // 2. Corrupt only this snapshot's prefix so the cases don't interfere with each other.
        deleteFromSnapshot(snapshotName, deleteIncludes);

        // 3. Bring up an OpenSearch target (also serves as the work coordinator).
        try (var target = new SearchClusterContainer(SearchClusterContainer.OS_V2_19_4)) {
            CompletableFuture.runAsync(target::start).join();

            var s3LocalDir = tempDir.resolve("s3-download");
            Files.createDirectories(s3LocalDir);

            var processBuilder = buildSolrMigrateProcess(
                snapshotName, s3RepoUri, s3LocalDir.toString(), target.getUrl(), collName);

            var result = runProcessCapturing(processBuilder, 240);

            log.atInfo().setMessage("RfsMigrateDocuments exited with {}").addArgument(result.exitCode).log();
            Assertions.assertTrue(
                result.output.contains("Non-retriable snapshot read failure"),
                "Expected a labeled snapshot-read-failure ERROR log before exit, but got:\n" + result.output);
            Assertions.assertEquals(
                RfsMigrateDocuments.SNAPSHOT_READ_FAILED_EXIT_CODE,
                result.exitCode,
                "Solr migration that can't read its S3 snapshot should exit with the snapshot-read-failure code.");
        }
    }

    private void deleteFromSnapshot(String snapshotName, String... includes) throws Exception {
        var cmd = new ArrayList<>(List.of(
            "awslocal", "s3", "rm",
            "s3://" + BUCKET_NAME + "/" + SUBPATH + "/" + snapshotName,
            "--recursive", "--exclude", "*"));
        for (var include : includes) {
            cmd.add("--include");
            cmd.add(include);
        }
        var rm = LOCAL_STACK.execInContainer(cmd.toArray(new String[0]));
        log.info("Deleted {} from snapshot '{}':\n{}", List.of(includes), snapshotName, rm.getStdout());
    }

    // ---- process launch ----

    private static ProcessBuilder buildSolrMigrateProcess(
        String snapshotName, String s3RepoUri, String s3LocalDir, String targetUrl, String collection
    ) {
        String classpath = System.getProperty("java.class.path");
        String javaExecutable = Paths.get(System.getProperty("java.home"), "bin", "java").toString();

        var args = new ArrayList<>(List.of(
            "--snapshot-name", snapshotName,
            "--source-version", "SOLR_8.11.4",
            "--s3-repo-uri", s3RepoUri,
            "--s3-region", REGION,
            "--s3-local-dir", s3LocalDir,
            "--s3-endpoint", localStackEndpoint(),
            "--target-host", targetUrl,
            "--coordinator-host", targetUrl,
            "--index-allowlist", collection,
            "--documents-per-bulk-request", "10",
            "--max-connections", "1",
            "--initial-lease-duration", "PT10M"
        ));

        log.atInfo().setMessage("Running RfsMigrateDocuments with args: {}").addArgument(args).log();
        var processBuilder = new ProcessBuilder(
            javaExecutable, "-cp", classpath, "org.opensearch.migrations.RfsMigrateDocuments");
        processBuilder.command().addAll(args);
        // The subprocess is a fresh JVM; production code uses DefaultCredentialsProvider, so pass
        // LocalStack's dummy credentials through the environment.
        processBuilder.environment().put("AWS_ACCESS_KEY_ID", "test");
        processBuilder.environment().put("AWS_SECRET_ACCESS_KEY", "test");
        processBuilder.environment().put("AWS_REGION", REGION);
        return processBuilder;
    }

    private static class ProcessResult {
        final int exitCode;
        final String output;

        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
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
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            Assertions.fail("The process did not finish within the timeout period (" + timeoutSeconds + " seconds).");
        }
        readerThread.join(TimeUnit.SECONDS.toMillis(5));
        synchronized (output) {
            return new ProcessResult(process.exitValue(), output.toString());
        }
    }

    // ---- Solr helpers ----

    private static String solrUrl() {
        return SOLR_CLOUD.getSolrUrl();
    }

    private static String localStackEndpoint() {
        return LOCAL_STACK.getEndpoint().toString();
    }

    private void createCollectionWithSchema(String name) throws Exception {
        SOLR_CLOUD.execInContainer("curl", "-sf",
            "http://localhost:8983/solr/admin/collections?action=CREATE"
                + "&name=" + name
                + "&numShards=1"
                + "&replicationFactor=1"
                + "&maxShardsPerNode=1"
                + "&wt=json");
        addField(name, "title", "string");
        log.info("Created collection '{}' with schema fields", name);
    }

    private void addField(String collection, String fieldName, String type) throws Exception {
        SOLR_CLOUD.execInContainer("curl", "-sf",
            "http://localhost:8983/solr/" + collection + "/schema",
            "-H", "Content-Type: application/json",
            "-d", "{\"add-field\":{\"name\":\"" + fieldName + "\",\"type\":\"" + type + "\",\"stored\":true}}");
    }

    private void indexOneDoc(String collection) throws Exception {
        SOLR_CLOUD.execInContainer("curl", "-sf",
            "http://localhost:8983/solr/" + collection + "/update?commit=true",
            "-H", "Content-Type: application/json",
            "-d", "[{\"id\":\"1\",\"title\":\"test\"}]");
        log.info("Indexed 1 doc into '{}'", collection);
    }
}
