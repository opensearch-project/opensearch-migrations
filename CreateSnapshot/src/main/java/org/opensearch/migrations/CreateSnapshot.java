package org.opensearch.migrations;

import java.util.List;

import org.opensearch.migrations.arguments.ArgLogUtils;
import org.opensearch.migrations.arguments.ArgNameConstants;
import org.opensearch.migrations.bulkload.common.ClusterVersionDetector;
import org.opensearch.migrations.bulkload.common.FileSystemSnapshotCreator;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.bulkload.common.S3SnapshotCreator;
import org.opensearch.migrations.bulkload.common.SnapshotCreator;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.solr.SolrSnapshotCreator;
import org.opensearch.migrations.bulkload.solr.SolrStandaloneBackupCreator;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts.ICreateSnapshotContext;
import org.opensearch.migrations.bulkload.worker.SnapshotRunner;
import org.opensearch.migrations.jcommander.EnvVarParameterPuller;
import org.opensearch.migrations.jcommander.JsonCommandLineParser;
import org.opensearch.migrations.snapshot.creation.tracing.RootSnapshotContext;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;
import org.opensearch.migrations.utils.ProcessHelpers;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.ParametersDelegate;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Slf4j
public class CreateSnapshot {
    public static class Args {
        @Parameter(
                names = {"--help", "-h"},
                help = true,
                description = "Displays information about how to use this tool")
        private boolean help;

        @Parameter(
                names = { "--snapshot-name" },
                required = true,
                description = "The name of the snapshot to migrate")
        public String snapshotName;

        @Parameter(
                names = { "--snapshot-repo-name" },
                required = true,
                description = "The name of the snapshot repository")
        public String snapshotRepoName;

        @Parameter(
                names = {"--file-system-repo-path" },
                required = false,
                description = "The full path to the snapshot repo on the file system.")
        public String fileSystemRepoPath;

        @Parameter(
                names = {"--s3-repo-uri" },
                required = false,
                description = "The S3 URI of the snapshot repo, like: s3://my-bucket/dir1/dir2")
        public String s3RepoUri;

        @Parameter(
                names = {"--s3-region" },
                required = false,
                description = "The AWS Region the S3 bucket is in, like: us-east-2")
        public String s3Region;

        @Parameter(
                names = {"--s3-endpoint" },
                required = false,
                description = "The S3 endpoint setting to specify when creating a snapshot repository")
        public String s3Endpoint;

        @ParametersDelegate
        public ConnectionContext.SourceArgs sourceArgs = new ConnectionContext.SourceArgs();

        @Parameter(
                names = {"--no-wait" },
                description = "Optional.  If provided, the snapshot runner will not wait for completion")
        public boolean noWait = false;

        @Parameter(
                names = {"--max-snapshot-rate-mb-per-node" },
                required = false,
                description = "The maximum snapshot rate in megabytes per second per node")
        public Integer maxSnapshotRateMBPerNode;

        @Parameter(
                names = {"--s3-role-arn" },
                required = false,
                description = "The role ARN the cluster will assume to write a snapshot to S3")
        public String s3RoleArn;

        @Parameter(
                names = {"--index-allowlist"},
                required = false,
                description = "A comma separated list of indices to include in the snapshot. If not provided, all indices are included.")
        public List<String> indexAllowlist = List.of();

        @Parameter(
                names = {"--compression-enabled"},
                required = false,
                description = "Whether to enable metadata compression for the snapshot. Defaults to false.")
        public boolean compressionEnabled = false;

        @Parameter(
                names = {"--include-global-state"},
                required = false,
                description = "Whether to include global state in the snapshot. Defaults to true.")
        public boolean includeGlobalState = true;

        @Parameter(
                required = false,
                names = {"--otel-collector-endpoint" },
                arity = 1,
                description = "Endpoint (host:port) for the OpenTelemetry Collector to which metrics logs should be"
                + "forwarded. If no value is provided, metrics will not be forwarded.")
        String otelCollectorEndpoint;

        @Parameter(
                names = {"--source-type"},
                required = false,
                description = "Source cluster type: 'elasticsearch' (default) or 'solr'")
        public String sourceType = "elasticsearch";

        @Parameter(
                names = {"--solr-collections"},
                required = false,
                description = "Comma-separated list of Solr collection names to back up (required when source-type=solr)")
        public List<String> solrCollections = List.of();
    }

    @Getter
    @AllArgsConstructor
    public static class S3RepoInfo {
        String awsRegion;
        String repoUri;
    }

    public static void main(String[] args) throws Exception {
        System.err.println("Starting program with: " + String.join(" ", ArgLogUtils.getRedactedArgs(args, ArgNameConstants.CENSORED_ARGS)));
        Args arguments = EnvVarParameterPuller.injectFromEnv(new Args(), "CREATE_SNAPSHOT_");
        var argParser = JsonCommandLineParser.newBuilder().addObject(arguments).build();
        argParser.parse(args);

        if (arguments.help) {
            argParser.getJCommander().usage();
            return;
        }

        var rootContext = new RootSnapshotContext(
            RootOtelContext.initializeOpenTelemetryWithCollectorOrAsNoop(arguments.otelCollectorEndpoint,
                RootSnapshotContext.SCOPE_NAME, ProcessHelpers.getNodeInstanceName()),
            new CompositeContextTracker(new ActiveContextTracker(), new ActiveContextTrackerByActivityType())
        );

        if (arguments.fileSystemRepoPath == null && arguments.s3RepoUri == null) {
            throw new ParameterException("Either file-system-repo-path or s3-repo-uri must be set");
        }
        if (arguments.fileSystemRepoPath != null && arguments.s3RepoUri != null) {
            throw new ParameterException("Only one of file-system-repo-path and s3-repo-uri can be set");
        }
        if (arguments.s3RepoUri != null && arguments.s3Region == null) {
            throw new ParameterException("If an s3 repo is being used, s3-region must be set");
        }

        var snapshotCreator = new CreateSnapshot(arguments, rootContext.createSnapshotCreateContext());
        snapshotCreator.run();
    }

    private Args arguments;
    private ICreateSnapshotContext context;

    public void run() {
        if ("solr".equalsIgnoreCase(arguments.sourceType)) {
            runSolrBackup();
            return;
        }
        // Auto-detect Solr from the source cluster
        boolean isSolr = false;
        try {
            var version = ClusterVersionDetector.detect(arguments.sourceArgs.toConnectionContext());
            if (version.getFlavor() == Flavor.SOLR) {
                log.info("Detected Solr source ({}), using Solr backup path", version);
                isSolr = true;
            }
        } catch (Exception e) {
            log.debug("Version detection failed, assuming Elasticsearch: {}", e.getMessage());
        }
        if (isSolr) {
            runSolrBackup();
            return;
        }
        runElasticsearchSnapshot();
    }

    private void runSolrBackup() {
        var backupLocation = arguments.fileSystemRepoPath != null
            ? arguments.fileSystemRepoPath : arguments.s3RepoUri;
        var solrUrl = arguments.sourceArgs.toConnectionContext().getUri().toString();
        var username = arguments.sourceArgs.getUsername();
        var password = arguments.sourceArgs.getPassword();

        // Auto-discover collections if not specified
        if (arguments.solrCollections.isEmpty()) {
            try {
                arguments.solrCollections = discoverSolrCollections(solrUrl, username, password);
                log.info("Auto-discovered {} Solr collection(s): {}", arguments.solrCollections.size(), arguments.solrCollections);
            } catch (Exception e) {
                throw new ParameterException("Failed to discover Solr collections: " + e.getMessage());
            }
        }

        if (isSolrCloud(solrUrl, username, password)) {
            runSolrCloudBackup(solrUrl, backupLocation, username, password);
        } else {
            runSolrStandaloneBackup(solrUrl, backupLocation, username, password);
        }
    }

    private boolean isSolrCloud(String solrUrl, String username, String password) {
        try {
            var url = solrUrl + "/solr/admin/collections?action=LIST&wt=json";
            var builder = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .GET()
                .timeout(java.time.Duration.ofSeconds(5));
            if (username != null && password != null) {
                builder.header("Authorization", "Basic " +
                    java.util.Base64.getEncoder().encodeToString((username + ":" + password).getBytes()));
            }
            var response = java.net.http.HttpClient.newHttpClient()
                .send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("SolrCloud detection interrupted, assuming standalone");
            return false;
        } catch (Exception e) {
            log.info("SolrCloud detection failed, assuming standalone: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Discover Solr collections (SolrCloud) or cores (standalone) via HTTP API.
     */
    private static List<String> discoverSolrCollections(String solrUrl, String username, String password) throws java.io.IOException {
        // Try SolrCloud Collections API first
        try {
            var json = solrHttpGet(solrUrl + "/solr/admin/collections?action=LIST&wt=json", username, password);
            var collections = parseJsonStringArray(json, "collections");
            if (!collections.isEmpty()) return collections;
        } catch (Exception e) {
            log.debug("Collections API failed, trying Core Admin: {}", e.getMessage());
        }
        // Fall back to Core Admin API (standalone)
        var json = solrHttpGet(solrUrl + "/solr/admin/cores?action=STATUS&wt=json", username, password);
        return parseJsonObjectKeys(json, "status");
    }

    /** Extract string array values from a top-level JSON field, e.g. {"collections":["a","b"]} → ["a","b"] */
    private static List<String> parseJsonStringArray(String json, String fieldName) {
        var result = new java.util.ArrayList<String>();
        var key = "\"" + fieldName + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return result;
        int arrStart = json.indexOf('[', idx);
        if (arrStart < 0) return result;
        int arrEnd = json.indexOf(']', arrStart);
        if (arrEnd < 0) return result;
        var arrContent = json.substring(arrStart + 1, arrEnd);
        for (var part : arrContent.split(",")) {
            var trimmed = part.trim();
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                result.add(trimmed.substring(1, trimmed.length() - 1));
            }
        }
        return result;
    }

    /** Extract top-level keys from a JSON object field, e.g. {"status":{"core1":{},"core2":{}}} → ["core1","core2"] */
    private static List<String> parseJsonObjectKeys(String json, String fieldName) {
        var result = new java.util.ArrayList<String>();
        var key = "\"" + fieldName + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return result;
        int objStart = json.indexOf('{', idx + key.length());
        if (objStart < 0) return result;
        int objEnd = findMatchingBrace(json, objStart);
        var objContent = json.substring(objStart + 1, objEnd);
        extractTopLevelKeys(objContent, result);
        return result;
    }

    /** Find the position of the closing brace matching the opening brace at {@code openPos}. */
    private static int findMatchingBrace(String s, int openPos) {
        int depth = 1;
        int pos = openPos + 1;
        while (pos < s.length() && depth > 0) {
            char c = s.charAt(pos);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            pos++;
        }
        return pos - 1;
    }

    /** Extract top-level quoted keys (followed by ':') from the content of a JSON object. */
    private static void extractTopLevelKeys(String objContent, List<String> result) {
        int i = 0;
        while (i < objContent.length()) {
            int qStart = objContent.indexOf('"', i);
            if (qStart < 0) return;
            int qEnd = objContent.indexOf('"', qStart + 1);
            if (qEnd < 0) return;
            i = processKeyCandidate(objContent, qStart, qEnd, result);
        }
    }

    /** Check if the quoted string is a key (followed by ':') and advance past its value. Returns next scan position. */
    private static int processKeyCandidate(String objContent, int qStart, int qEnd, List<String> result) {
        int colon = objContent.indexOf(':', qEnd);
        if (colon < 0 || !objContent.substring(qEnd + 1, colon).trim().isEmpty()) {
            return qEnd + 1;
        }
        result.add(objContent.substring(qStart + 1, qEnd));
        int valStart = objContent.indexOf('{', colon);
        if (valStart >= 0) {
            return findMatchingBrace(objContent, valStart) + 1;
        }
        return colon + 1;
    }

    private static String solrHttpGet(String url, String username, String password) throws java.io.IOException {
        var builder = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(url))
            .GET()
            .timeout(java.time.Duration.ofSeconds(10));
        if (username != null && password != null) {
            builder.header("Authorization", "Basic " +
                java.util.Base64.getEncoder().encodeToString((username + ":" + password).getBytes()));
        }
        try {
            var response = java.net.http.HttpClient.newHttpClient()
                .send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new java.io.IOException("HTTP " + response.statusCode() + " from " + url);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("Interrupted during HTTP request to " + url, e);
        }
    }

    private void runSolrCloudBackup(String solrUrl, String backupLocation, String username, String password) {
        log.info("Detected SolrCloud — using Collections API backup");
        var solrCreator = new SolrSnapshotCreator(
            solrUrl, arguments.snapshotName, backupLocation,
            arguments.solrCollections, username, password
        );
        solrCreator.registerRepo();
        solrCreator.createSnapshot();
        waitForCompletion(solrCreator::isSnapshotFinished);
    }

    private void runSolrStandaloneBackup(String solrUrl, String backupLocation, String username, String password) {
        log.info("Detected standalone Solr — using replication API backup");
        var creator = new SolrStandaloneBackupCreator(
            solrUrl, arguments.snapshotName, backupLocation,
            arguments.solrCollections, username, password
        );
        creator.createBackup();
        waitForCompletion(creator::isBackupFinished);
    }

    private void waitForCompletion(java.util.function.BooleanSupplier isFinished) {
        if (!arguments.noWait) {
            log.info("Waiting for Solr backup to complete...");
            while (!isFinished.getAsBoolean()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SolrSnapshotCreator.SolrBackupFailed("Interrupted while waiting for backup");
                }
            }
            log.info("Solr backup '{}' completed", arguments.snapshotName);
        } else {
            log.info("Solr backup '{}' initiated (no-wait mode)", arguments.snapshotName);
        }
    }

    private void runElasticsearchSnapshot() {
        var clientFactory = new OpenSearchClientFactory(arguments.sourceArgs.toConnectionContext());
        var client = clientFactory.determineVersionAndCreate();
        SnapshotCreator snapshotCreator;
        if (arguments.fileSystemRepoPath != null) {
            snapshotCreator = new FileSystemSnapshotCreator(
                    arguments.snapshotName,
                    arguments.snapshotRepoName,
                    client,
                    arguments.fileSystemRepoPath,
                    arguments.indexAllowlist,
                    context,
                    arguments.compressionEnabled,
                    arguments.includeGlobalState
                );
        } else {
            snapshotCreator = new S3SnapshotCreator(
                arguments.snapshotName,
                arguments.snapshotRepoName,
                client,
                arguments.s3RepoUri,
                arguments.s3Region,
                arguments.s3Endpoint,
                arguments.indexAllowlist,
                arguments.maxSnapshotRateMBPerNode,
                arguments.s3RoleArn,
                context,
                arguments.compressionEnabled,
                arguments.includeGlobalState
            );
        }

        try {
            if (arguments.noWait) {
                SnapshotRunner.run(snapshotCreator);
            } else {
                SnapshotRunner.runAndWaitForCompletion(snapshotCreator);
            }
        } catch (Exception e) {
            log.atError().setCause(e).setMessage("Unexpected error running CreateSnapshot").log();
            throw e;
        }
    }
}
