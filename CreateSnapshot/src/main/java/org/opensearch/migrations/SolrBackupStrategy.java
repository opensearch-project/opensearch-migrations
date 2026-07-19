package org.opensearch.migrations;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.opensearch.migrations.bulkload.common.RepoUri;
import org.opensearch.migrations.bulkload.common.S3Uri;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.solr.SolrHttpClient;
import org.opensearch.migrations.bulkload.solr.SolrSnapshotCreator;
import org.opensearch.migrations.bulkload.solr.SolrStandaloneBackupCreator;

import com.beust.jcommander.ParameterException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Unified backup/import strategy for Solr sources. Both standalone and SolrCloud
 * clusters always invoke this strategy through {@link CreateSnapshot}, with the
 * {@link SnapshotMode} flag controlling behavior:
 * <ul>
 *   <li>{@code CREATE} — performs a full snapshot (backup) of the Solr cluster</li>
 *   <li>{@code IMPORT} — config retrieval + external snapshot import workflow</li>
 * </ul>
 *
 * <p>Within each mode, standalone vs distributed (SolrCloud) differences are handled
 * internally — callers never need to select a separate code path based on cluster topology.
 */
@Slf4j
public class SolrBackupStrategy implements SourceBackupStrategy {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CreateSnapshot.Args args;
    private final ConnectionContext connectionContext;
    private final SolrHttpClient httpClient;
    private final SnapshotMode mode;
    private final RepoUri repoUri;
    private final boolean isCloud;

    public SolrBackupStrategy(CreateSnapshot.Args args) {
        this.args = args;
        this.repoUri = RepoUri.parse(args.repoUri);
        validateSolrRepoSupported(repoUri);
        this.connectionContext = args.sourceArgs.toConnectionContext();
        this.httpClient = new SolrHttpClient(connectionContext);
        this.mode = CreateSnapshot.getSnapshotMode(args);
        this.isCloud = isSolrCloud(connectionContext.getUri().toString(), httpClient);
    }

    private static final String COLLECTION_LABEL = "collection";
    private static final String CORE_LABEL = "core";

    /**
     * Required config files for Solr migration. The downstream pipeline (SolrSchemaXmlParser,
     * SolrBackupLayout, MetadataMigration) needs these to produce OpenSearch index mappings.
     */
    private static final List<String> REQUIRED_CONFIG_FILES = List.of(
        "managed-schema.xml"
    );

    /** Timeout for the SolrCloud-vs-standalone probe. */
    private static final Duration DETECTION_TIMEOUT = Duration.ofSeconds(10);

    private String topologyLabel() {
        return isCloud ? COLLECTION_LABEL : CORE_LABEL;
    }

    /**
     * Thrown when IMPORT mode cannot obtain the source schema for a collection/core and therefore
     * cannot guarantee that the downstream metadata migration will produce correct mappings.
     *
     * <p>IMPORT mode exists precisely to upload the schema into an externally-managed snapshot. If
     * the schema cannot be fetched (e.g. the live Solr source is unreachable, or the file handler
     * and Schema API both fail), continuing would leave the snapshot without a schema and the
     * migration would silently produce empty/wrong mappings with a green exit. Failing loudly here
     * surfaces the problem at import time instead. (CREATE mode keeps the historical best-effort
     * behavior, since the backup itself — SolrCloud BACKUP — carries the configs.)
     */
    public static class SolrImportSchemaUnavailable extends RuntimeException {
        public SolrImportSchemaUnavailable(String message) {
            super(message);
        }

        public SolrImportSchemaUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Thrown when the Solr topology can't be determined (unreachable, timeout, or auth-blocked). */
    public static class SolrTopologyDetectionException extends RuntimeException {
        public SolrTopologyDetectionException(String message) {
            super(message);
        }

        public SolrTopologyDetectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Unified entry point for Solr backup/import operations.
     *
     * <p>Execution flow:
     * <ol>
     *   <li>Shared: resolve collections (auto-discover if not specified)</li>
     *   <li>Shared: ensure required config files are present in S3</li>
     *   <li>Branch on mode:
     *     <ul>
     *       <li>{@code CREATE} → perform snapshot backup (cloud or standalone)</li>
     *       <li>{@code IMPORT} → validate external snapshot accessibility</li>
     *     </ul>
     *   </li>
     * </ol>
     */
    @Override
    public void run() {
        var parsedUri = repoUri;
        // Resolve file repos to the scheme-less filesystem path. Solr's backup APIs expect a
        // bare path for local filesystem locations (the legacy standalone replication handler
        // joins `location` onto the core data dir, so a leading file:// scheme produces a
        // mangled path and an HTTP 400). S3/GCS keep the raw URI — the cloud path strips the
        // scheme/bucket downstream (buildPerCollectionLocation), and the standalone S3 branch
        // re-derives the key from the raw URI itself.
        var backupLocation = parsedUri instanceof RepoUri.FileRepoUri f ? f.path() : parsedUri.rawUri();
        var solrUrl = connectionContext.getUri().toString();
        log.info("Running SolrBackupStrategy: mode={}, topology={}", mode, isCloud ? "SolrCloud" : "standalone");

        // ── Shared steps (both modes) ──────────────────────────────────────────
        resolveCollections(solrUrl);

        // Synthetic config upload runs only for standalone Solr, in BOTH modes.
        // Standalone replication backups write only Lucene index data (no ZooKeeper
        // configs), so we must fetch the schema from the live source and place it in the
        // synthetic zk_backup_0/configs/ structure the downstream RFS reader expects.
        //
        // SolrCloud is skipped in BOTH CREATE and IMPORT modes: the Collections API
        // BACKUP writes zk_backup_0/configs/ from ZooKeeper itself, two levels deep
        // (<snapshot>/<collection>/<collection>/zk_backup_0/...). Pre-uploading a
        // synthetic schema at the shallow <snapshot>/<collection>/zk_backup_0/ path
        // would shadow that real layout: SolrBackupLayout.resolveCollectionDataPrefix
        // matches the shallow zk_backup first and never descends to the real data, so
        // the RFS worker finds the schema but no shard_backup_metadata/index and fails
        // with "No Lucene segments found in backup directory".
        if (!isCloud) {
            ensureConfigFilesInS3(solrUrl, parsedUri);
            ensureConfigFilesOnFilesystem(solrUrl, parsedUri);
        }

        // ── Mode-specific branching ────────────────────────────────────────────
        executeModeSpecificOperation(solrUrl, backupLocation, parsedUri);
    }

    private static void validateSolrRepoSupported(RepoUri parsedUri) {
        // Solr + GCS is intentionally out of scope for this release: the metadata
        // (ClusterReaderExtractor) and document (RfsMigrateDocuments) read paths only
        // handle file:// and s3:// for Solr sources. Reject gs:// so a user cannot create or
        // import-prepare a Solr snapshot in a repository the downstream stages cannot read.
        if (parsedUri instanceof RepoUri.GcsRepoUri) {
            throw new ParameterException(
                "Solr backup to gs:// is not supported in this release; use --repo-uri with a file:// or s3:// scheme.");
        }
    }

    /**
     * Dispatches to the appropriate mode-specific operation after shared config
     * validation has completed. This is the single divergence point between
     * CREATE and IMPORT workflows.
     */
    private void executeModeSpecificOperation(String solrUrl, String backupLocation, RepoUri parsedUri) {
        switch (mode) {
            case CREATE:
                runCreateMode(solrUrl, backupLocation, parsedUri);
                break;
            case IMPORT:
                runImportMode(solrUrl, parsedUri);
                break;
            default:
                throw new IllegalStateException("Unsupported snapshot mode: " + mode);
        }
    }

    /**
     * Shared config-file check-and-upload logic that runs in BOTH import and create modes.
     * For each collection/core, verifies that all required config files exist in S3. If any are
     * missing, retrieves them from the Solr source cluster and uploads them.
     *
     * <p>This ensures the downstream migration pipeline always has the schema/config data
     * it needs, regardless of whether we're creating a new snapshot or importing an existing one.
     *
     * <p><strong>Standalone note:</strong> Standalone Solr replication backups do NOT produce
     * {@code zk_backup_N/} directories. This method synthetically creates the expected
     * {@code zk_backup_0/configs/{name}/managed-schema.xml} structure in S3 so that
     * {@link org.opensearch.migrations.bulkload.solr.SolrSchemaXmlParser#findAndParse}
     * can locate the schema during the downstream metadata migration phase.
     */
    private void ensureConfigFilesInS3(String solrUrl, RepoUri parsedUri) {
        if (!(parsedUri instanceof RepoUri.S3RepoUri s3RepoUri)) {
            log.info("No S3 repo configured — skipping config file check (filesystem mode)");
            return;
        }

        var repoUri = s3RepoUri.s3Uri();
        var snapshotPrefix = computeParentPrefix(repoUri.key) + args.snapshotName + "/";

        try (var s3Client = buildS3Client(args.s3Region, args.endpoint)) {
            for (var collection : args.solrCollections) {
                for (var configFile : REQUIRED_CONFIG_FILES) {
                    uploadConfigFileToS3IfMissing(s3Client, repoUri, snapshotPrefix, solrUrl, collection, configFile);
                }
            }
        } catch (SolrImportSchemaUnavailable e) {
            // IMPORT mode: schema upload is the whole point of the step, so this is fatal —
            // never downgrade it to a warning (that would reintroduce the silent-empty-mappings bug).
            throw e;
        } catch (Exception e) {
            if (mode == SnapshotMode.IMPORT) {
                throw new SolrImportSchemaUnavailable(
                    "IMPORT mode could not ensure required Solr config files in S3: " + e.getMessage(), e);
            }
            log.warn("Config file check-and-upload failed: {} — migration may see empty mappings", e.getMessage());
        }
    }

    private void uploadConfigFileToS3IfMissing(S3Client s3Client, S3Uri repoUri, String snapshotPrefix,
            String solrUrl, String collection, String configFile) {
        var configKey = snapshotPrefix + collection + "/zk_backup_0/configs/" + collection + "/" + configFile;

        if (s3ObjectExists(s3Client, repoUri.bucketName, configKey)) {
            log.info("Config '{}' already present in S3 for {} '{}', skipping",
                configFile, topologyLabel(), collection);
            return;
        }

        log.info("Config '{}' missing in S3 for {} '{}', fetching from source",
            configFile, topologyLabel(), collection);
        var content = fetchConfigFile(solrUrl, collection, configFile);
        if (content == null) {
            if (mode == SnapshotMode.IMPORT) {
                throw new SolrImportSchemaUnavailable(String.format(
                    "IMPORT mode could not retrieve required config '%s' for %s '%s' from the Solr source at %s. "
                        + "The live source must be reachable so its schema can be uploaded into the snapshot; "
                        + "otherwise the metadata migration would produce empty/incorrect mappings.",
                    configFile, topologyLabel(), collection, solrUrl));
            }
            log.warn("Could not retrieve '{}' for {} '{}' from source — "
                + "downstream migration may see empty mappings",
                configFile, topologyLabel(), collection);
            return;
        }

        s3Client.putObject(
            PutObjectRequest.builder().bucket(repoUri.bucketName).key(configKey).build(),
            RequestBody.fromString(content, java.nio.charset.StandardCharsets.UTF_8));
        log.info("Uploaded '{}' for {} '{}' to s3://{}/{}",
            configFile, topologyLabel(), collection, repoUri.bucketName, configKey);
    }

    /**
     * Filesystem-based config check-and-write for standalone backups that target local disk.
     * Standalone replication backups only write Lucene index data; they do NOT include
     * ZooKeeper config snapshots. This creates the synthetic {@code zk_backup_0/configs/}
     * directory structure that {@link org.opensearch.migrations.bulkload.solr.SolrSchemaXmlParser}
     * expects, fetching configs from the live cluster.
     *
     * <p>Only runs when using filesystem repo (not S3) AND the source is standalone.
     * SolrCloud filesystem backups already include zk_backup via the Collections API BACKUP.
     */
    private void ensureConfigFilesOnFilesystem(String solrUrl, RepoUri parsedUri) {
        if (!(parsedUri instanceof RepoUri.FileRepoUri fileRepoUri)) {
            return; // S3 mode handled by ensureConfigFilesInS3
        }
        if (isCloud) {
            return; // SolrCloud BACKUP includes zk_backup automatically
        }

        for (var core : args.solrCollections) {
            var configDir = Paths.get(fileRepoUri.path(), args.snapshotName, core,
                "zk_backup_0", "configs", core);
            for (var configFile : REQUIRED_CONFIG_FILES) {
                writeConfigFileToFilesystemIfMissing(configDir, solrUrl, core, configFile);
            }
        }
    }

    private void writeConfigFileToFilesystemIfMissing(java.nio.file.Path configDir, String solrUrl,
            String core, String configFile) {
        var targetFile = configDir.resolve(configFile);
        if (Files.exists(targetFile)) {
            log.info("Config '{}' already present on filesystem for core '{}'", configFile, core);
            return;
        }

        log.info("Config '{}' missing on filesystem for core '{}', fetching from source", configFile, core);
        var content = fetchConfigFile(solrUrl, core, configFile);
        if (content == null) {
            if (mode == SnapshotMode.IMPORT) {
                throw new SolrImportSchemaUnavailable(String.format(
                    "IMPORT mode could not retrieve required config '%s' for core '%s' from the Solr source at %s. "
                        + "The live source must be reachable so its schema can be written into the snapshot; "
                        + "otherwise the metadata migration would produce empty/incorrect mappings.",
                    configFile, core, solrUrl));
            }
            log.warn("Could not retrieve '{}' for core '{}' — downstream may see empty mappings",
                configFile, core);
            return;
        }

        try {
            Files.createDirectories(configDir);
            Files.writeString(targetFile, content);
            log.info("Wrote '{}' for core '{}' to {}", configFile, core, targetFile);
        } catch (IOException e) {
            if (mode == SnapshotMode.IMPORT) {
                throw new SolrImportSchemaUnavailable(
                    "IMPORT mode could not write required Solr config file to " + targetFile + ": " + e.getMessage(),
                    e);
            }
            log.warn("Failed to write config file {}: {}", targetFile, e.getMessage());
        }
    }

    /**
     * Fetch a specific config file from the Solr source. Tries multiple retrieval strategies
     * to handle both SolrCloud and standalone topologies:
     * <ol>
     *   <li>Admin File Handler ({@code /admin/file?file=...}) — works for both SolrCloud
     *       (reads from ZooKeeper) and standalone (reads from core's conf/ directory)</li>
     *   <li>Schema API ({@code /schema?wt=xml}) — standalone fallback when the file handler
     *       is not configured or schema is generated dynamically (schemaless mode)</li>
     * </ol>
     *
     * <p>For schema files, tries naming variants (managed-schema, managed-schema.xml, schema.xml)
     * since Solr versions use different filenames.
     */
    private String fetchConfigFile(String solrUrl, String collection, String configFile) {
        var result = fetchViaFileHandler(solrUrl, collection, configFile);
        if (result != null) {
            return result;
        }

        // Strategy 2: Schema API fallback (standalone cores where file handler may be disabled)
        if (!isCloud && configFile.contains("schema")) {
            return fetchViaSchemaApi(solrUrl, collection);
        }

        return null;
    }

    private String fetchViaFileHandler(String solrUrl, String collection, String configFile) {
        var variants = configFile.contains("schema")
            ? List.of("managed-schema", "managed-schema.xml", "schema.xml")
            : List.of(configFile);

        for (var fileName : variants) {
            var url = solrUrl + "/solr/" + collection + "/admin/file?file=" + fileName + "&contentType=text/xml";
            try {
                var body = httpClient.getString(url, Duration.ofSeconds(30));
                if (body != null && !body.isBlank()) {
                    log.info("Fetched '{}' for {} '{}' via file handler",
                        fileName, topologyLabel(), collection);
                    return body;
                }
            } catch (Exception e) {
                log.debug("Config file '{}' not available for '{}' via file handler: {}",
                    fileName, collection, e.getMessage());
            }
        }
        return null;
    }

    private String fetchViaSchemaApi(String solrUrl, String collection) {
        var schemaUrl = solrUrl + "/solr/" + collection + "/schema?wt=schema.xml";
        try {
            var body = httpClient.getString(schemaUrl, Duration.ofSeconds(30));
            if (body != null && !body.isBlank()) {
                log.info("Fetched schema for core '{}' via Schema API fallback", collection);
                return body;
            }
        } catch (Exception e) {
            log.debug("Schema API fallback failed for core '{}': {}", collection, e.getMessage());
        }
        return null;
    }

    /**
     * CREATE mode: perform a full snapshot/backup of the Solr cluster.
     * Handles both SolrCloud (Collections API BACKUP) and standalone (replication API).
     *
     * <p><strong>Standalone topology differences handled here:</strong>
     * <ul>
     *   <li>Uses replication handler ({@code /replication?command=backup}) instead of
     *       Collections API BACKUP — backs up each core individually</li>
     *   <li>Produces {@code snapshot.{name}/} layout instead of
     *       {@code {collection}/zk_backup_N/} layout</li>
     *   <li>Config files (schema) are retrieved via file handler or Schema API and uploaded
     *       to S3 in the synthetic {@code zk_backup_0/configs/} structure by
     *       {@link #ensureConfigFilesInS3}, since standalone backups don't include configs</li>
     *   <li>Core names are used in place of collection names throughout</li>
     * </ul>
     */
    private void runCreateMode(String solrUrl, String backupLocation, RepoUri parsedUri) {
        log.info("CREATE mode: backing up {} collection(s) to {}", args.solrCollections.size(), backupLocation);

        if (isCloud) {
            runCloudBackup(solrUrl, backupLocation, parsedUri);
        } else {
            runStandaloneBackup(solrUrl, backupLocation, parsedUri);
        }
    }

    /**
     * IMPORT mode: prepare an externally-provided snapshot for the downstream migration pipeline.
     * For standalone Solr, required config/schema files have already been fetched from the live
     * source and uploaded by ensureConfigFilesInS3()/ensureConfigFilesOnFilesystem() — and that
     * step is FATAL in IMPORT mode (it throws {@link SolrImportSchemaUnavailable} if the schema
     * cannot be obtained), so by the time we get here the schema is guaranteed present. SolrCloud
     * import relies on the external snapshot's real ZooKeeper config layout.
     *
     * <p>Unlike CREATE mode, no backup operation is performed — the snapshot data must already
     * exist at the configured location (placed there by an external process or prior backup).
     *
     * <p><strong>Live-source requirement:</strong> IMPORT mode fetches the schema from the running
     * standalone Solr source, and always requires source reachability for collection/core
     * resolution. It is not an offline operation against a decommissioned source.
     */
    @SuppressWarnings("java:S1172") // solrUrl reserved for future import validation against live cluster
    private void runImportMode(String solrUrl, RepoUri parsedUri) {
        var backupLocation = parsedUri.rawUri();
        log.info("IMPORT mode: verifying snapshot location accessibility for {} collection(s) at {}",
            args.solrCollections.size(), backupLocation);

        if (parsedUri instanceof RepoUri.S3RepoUri s3RepoUri) {
            validateS3SnapshotAccessible(s3RepoUri.s3Uri());
        } else if (parsedUri instanceof RepoUri.FileRepoUri fileRepoUri) {
            validateFileSystemSnapshotAccessible(fileRepoUri.path());
        }

        log.info("IMPORT mode complete: config files ensured, snapshot location verified at {}", backupLocation);
    }

    /**
     * Validates that the S3 snapshot location is accessible (bucket exists, prefix is listable).
     */
    private void validateS3SnapshotAccessible(S3Uri repoUri) {
        var snapshotPrefix = computeParentPrefix(repoUri.key) + args.snapshotName + "/";

        try (var s3Client = buildS3Client(args.s3Region, args.endpoint)) {
            var response = s3Client.listObjectsV2(
                software.amazon.awssdk.services.s3.model.ListObjectsV2Request.builder()
                    .bucket(repoUri.bucketName)
                    .prefix(snapshotPrefix)
                    .maxKeys(1)
                    .build());
            if (response.contents().isEmpty()) {
                log.warn("No objects found at s3://{}/{} — downstream pipeline may fail if snapshot data "
                    + "has not been uploaded yet", repoUri.bucketName, snapshotPrefix);
            } else {
                log.info("Snapshot data confirmed at s3://{}/{}", repoUri.bucketName, snapshotPrefix);
            }
        } catch (Exception e) {
            log.warn("Could not verify S3 snapshot location s3://{}/{}: {} — proceeding anyway",
                repoUri.bucketName, snapshotPrefix, e.getMessage());
        }
    }

    /**
     * Validates that the filesystem snapshot location exists and is readable.
     */
    private void validateFileSystemSnapshotAccessible(String repoPath) {
        var snapshotDir = Paths.get(repoPath, args.snapshotName);
        if (Files.exists(snapshotDir) && Files.isDirectory(snapshotDir)) {
            log.info("Snapshot directory confirmed at {}", snapshotDir);
        } else {
            log.warn("Snapshot directory not found at {} — downstream pipeline may fail if snapshot data "
                + "has not been placed there yet", snapshotDir);
        }
    }

    // ---- Collection resolution (shared across modes) ----

    private void resolveCollections(String solrUrl) {
        if (args.solrCollections.isEmpty()) {
            try {
                args.solrCollections = discoverCollections(solrUrl, httpClient);
                log.info("Auto-discovered {} Solr {}: {}",
                    args.solrCollections.size(),
                    isCloud ? "collection(s)" : "core(s)",
                    args.solrCollections);
            } catch (Exception e) {
                throw new ParameterException("Failed to discover Solr collections/cores: " + e.getMessage());
            }
        }

        if (args.solrCollections.isEmpty()) {
            throw new ParameterException(
                isCloud
                    ? "No Solr collections found. Specify --solr-collections explicitly."
                    : "No Solr cores found. Specify --solr-collections with core names explicitly.");
        }
    }

    // ---- Cloud vs standalone detection ----

    static boolean isSolrCloud(String solrUrl, SolrHttpClient httpClient) {
        var listUrl = solrUrl + "/solr/admin/collections?action=LIST&wt=json";
        HttpResponse<String> response;
        try {
            response = httpClient.getRaw(listUrl, DETECTION_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SolrTopologyDetectionException(
                "Interrupted while detecting Solr topology at " + solrUrl, e);
        } catch (IOException e) {
            // No HTTP response (unreachable, refused, TLS, timeout): fail loudly rather than guess.
            throw new SolrTopologyDetectionException(
                "Could not reach Solr at " + solrUrl + " to detect SolrCloud vs standalone topology: "
                    + e.getMessage(), e);
        }

        int status = response.statusCode();
        if (status == 200) {
            return true;
        }
        if (status == 401 || status == 403) {
            // Access-controlled: can't read the Collections API to decide, so don't guess standalone.
            throw new SolrTopologyDetectionException(
                "Solr authentication/authorization failed (HTTP " + status + ") while detecting topology at "
                    + solrUrl + "; cannot determine SolrCloud vs standalone");
        }
        // Reachable non-200 (standalone Solr returns HTTP 400 "not running in SolrCloud mode").
        log.info("Solr topology detection: HTTP {} from Collections API at {} — treating as standalone",
            status, solrUrl);
        return false;
    }

    // ---- Collection / core discovery ----

    static List<String> discoverCollections(String solrUrl, SolrHttpClient httpClient) throws IOException {
        // Try SolrCloud Collections API first
        try {
            var json = httpClient.getString(
                solrUrl + "/solr/admin/collections?action=LIST&wt=json", Duration.ofSeconds(10));
            var node = MAPPER.readTree(json).path("collections");
            var collections = new ArrayList<String>();
            if (node.isArray()) {
                node.forEach(n -> collections.add(n.asText()));
            }
            if (!collections.isEmpty()) return collections;
        } catch (Exception e) {
            log.debug("Collections API failed, trying Core Admin: {}", e.getMessage());
        }
        // Fall back to Core Admin API (standalone)
        var json = httpClient.getString(
            solrUrl + "/solr/admin/cores?action=STATUS&wt=json", Duration.ofSeconds(10));
        return objectFieldKeys(MAPPER.readTree(json), "status");
    }

    private static List<String> objectFieldKeys(JsonNode root, String fieldName) {
        var result = new ArrayList<String>();
        collectObjectFieldKeys(root, fieldName, result);
        return result;
    }

    private static boolean collectObjectFieldKeys(JsonNode node, String fieldName, List<String> result) {
        if (node.has(fieldName) && node.get(fieldName).isObject()) {
            node.get(fieldName).fieldNames().forEachRemaining(result::add);
            return true;
        }
        for (JsonNode child : node) {
            if (child.isObject() && collectObjectFieldKeys(child, fieldName, result)) {
                return true;
            }
        }
        return false;
    }

    // ---- CREATE mode: backup execution ----

    private void runCloudBackup(String solrUrl, String backupLocation, RepoUri parsedUri) {
        log.info("Detected SolrCloud — using Collections API backup");
        switch (parsedUri) {
            case RepoUri.S3RepoUri s -> ensureS3LocationExists(s.rawUri(), args.snapshotName, args.s3Region, args.endpoint);
            case RepoUri.FileRepoUri f -> ensureFileSystemLocationExists(f.path(), args.snapshotName);
            case RepoUri.GcsRepoUri g -> {} // GCS doesn't require pre-created directories
        }
        var creator = new SolrSnapshotCreator(
            solrUrl, args.snapshotName, backupLocation,
            args.solrCollections, connectionContext, args.snapshotRepoName
        );
        creator.registerRepo();
        creator.createSnapshot();
        waitForCompletion(creator::isSnapshotFinished);
    }

    private void runStandaloneBackup(String solrUrl, String backupLocation, RepoUri parsedUri) {
        log.info("Detected standalone Solr — using replication API backup");
        String repositoryName = null;
        // For cloud repositories (S3/GCS) the bucket is configured in solr.xml; Solr's `location`
        // must be the bucket-relative path/key, NOT the full s3://bucket/... or gs://bucket/... URI.
        // Strip the scheme+bucket to the in-bucket path (leading slash removed) for both.
        if (parsedUri instanceof RepoUri.S3RepoUri || parsedUri instanceof RepoUri.GcsRepoUri) {
            repositoryName = args.snapshotRepoName;
            var path = URI.create(parsedUri.rawUri()).getPath();
            backupLocation = path == null ? "" : path;
            if (backupLocation.startsWith("/")) {
                backupLocation = backupLocation.substring(1);
            }
            var scheme = parsedUri instanceof RepoUri.S3RepoUri ? "S3" : "GCS";
            log.info("Using {} backup repository '{}' with location prefix '{}'", scheme, repositoryName, backupLocation);
            if (parsedUri instanceof RepoUri.S3RepoUri s3RepoUri) {
                ensureS3ParentLocationExists(s3RepoUri.rawUri(), args.s3Region, args.endpoint);
            }
        }
        var creator = new SolrStandaloneBackupCreator(
            solrUrl, args.snapshotName, backupLocation,
            args.solrCollections, connectionContext, repositoryName
        );
        creator.createBackup();
        waitForCompletion(creator::isBackupFinished);
        // Config files already ensured by ensureConfigFilesInS3()/ensureConfigFilesOnFilesystem()
    }

    // ---- S3 utilities ----

    private boolean s3ObjectExists(S3Client s3Client, String bucket, String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    private void ensureS3ParentLocationExists(String s3RepoUri, String region, String endpoint) {
        var repoUri = new S3Uri(s3RepoUri);
        var parentPrefix = computeParentPrefix(repoUri.key);
        if (parentPrefix.isEmpty()) {
            return;
        }
        try (var s3Client = buildS3Client(region, endpoint)) {
            log.info("Ensuring S3 parent directory marker: s3://{}/{}", repoUri.bucketName, parentPrefix);
            createDirectoryMarkerIfMissing(s3Client, repoUri.bucketName, parentPrefix);
        } catch (Exception e) {
            log.warn("Failed to ensure S3 parent directory marker under s3://{}/{}: {} — continuing.",
                repoUri.bucketName, parentPrefix, e.getMessage());
        }
    }

    private void waitForCompletion(BooleanSupplier isFinished) {
        if (!args.noWait) {
            log.info("Waiting for Solr backup to complete...");
            while (!isFinished.getAsBoolean()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SolrSnapshotCreator.SolrBackupFailed("Interrupted while waiting for backup");
                }
            }
            log.info("Solr backup '{}' completed", args.snapshotName);
        } else {
            log.info("Solr backup '{}' initiated (no-wait mode)", args.snapshotName);
        }
    }

    private void ensureS3LocationExists(String s3RepoUri, String snapshotName, String region, String endpoint) {
        var repoUri = new S3Uri(s3RepoUri);
        var parentPrefix = computeParentPrefix(repoUri.key);
        var snapshotKey = parentPrefix + snapshotName + "/";

        try (var s3Client = buildS3Client(region, endpoint)) {
            if (!parentPrefix.isEmpty()) {
                log.info("Ensuring S3 parent directory marker: s3://{}/{}", repoUri.bucketName, parentPrefix);
                createDirectoryMarkerIfMissing(s3Client, repoUri.bucketName, parentPrefix);
            }
            log.info("Ensuring S3 snapshot directory marker: s3://{}/{}", repoUri.bucketName, snapshotKey);
            createDirectoryMarkerIfMissing(s3Client, repoUri.bucketName, snapshotKey);
        } catch (Exception e) {
            log.warn("Failed to ensure S3 directory markers under s3://{}/{}: {} — continuing.",
                repoUri.bucketName, snapshotKey, e.getMessage());
        }
    }

    static String computeParentPrefix(String parentKey) {
        if (parentKey == null || parentKey.isEmpty()) {
            return "";
        }
        if (parentKey.endsWith("/")) {
            return parentKey;
        }
        return parentKey + "/";
    }

    private static S3Client buildS3Client(String region, String endpoint) {
        var builder = S3Client.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isEmpty()) {
            var endpointUri = endpoint.contains("://") ? endpoint : "http://" + endpoint;
            builder.endpointOverride(URI.create(endpointUri));
            builder.forcePathStyle(true);
            log.info("Using custom S3 endpoint: {} (path-style)", endpointUri);
        }
        return builder.build();
    }

    private void createDirectoryMarkerIfMissing(S3Client s3Client, String bucket, String dirKey) {
        if (s3ObjectExists(s3Client, bucket, dirKey)) {
            log.info("S3 directory marker already exists");
            return;
        }
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(dirKey)
                .contentType("application/x-directory")
                .build(),
            RequestBody.empty());
        log.info("Created S3 directory marker: s3://{}/{}", bucket, dirKey);
    }

    private void ensureFileSystemLocationExists(String fileSystemRepoPath, String snapshotName) {
        try {
            var snapshotDir = Paths.get(fileSystemRepoPath, snapshotName);
            log.info("Ensuring filesystem backup directory: {}", snapshotDir);
            Files.createDirectories(snapshotDir);
        } catch (Exception e) {
            log.warn("Failed to create filesystem backup directory {}/{}: {} — continuing.",
                fileSystemRepoPath, snapshotName, e.getMessage());
        }
    }
}
