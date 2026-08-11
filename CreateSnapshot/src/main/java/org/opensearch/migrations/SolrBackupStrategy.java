package org.opensearch.migrations;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.RepoUri;
import org.opensearch.migrations.bulkload.common.S3Uri;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.solr.SolrHttpClient;
import org.opensearch.migrations.bulkload.solr.SolrSnapshotCreator;
import org.opensearch.migrations.bulkload.solr.SolrStandaloneBackupCreator;

import com.beust.jcommander.ParameterException;
import com.fasterxml.jackson.core.JsonProcessingException;
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

    /**
     * SolrCloud vs standalone, resolved from the cheapest source that can answer (see
     * {@link #resolveTopologyAndCollections}). Null until resolved — never read directly, use
     * {@link #isCloud()}.
     */
    private Boolean cloudTopology;

    public SolrBackupStrategy(CreateSnapshot.Args args) {
        this.args = args;
        this.repoUri = RepoUri.parse(args.repoUri);
        validateSolrRepoSupported(repoUri);
        this.connectionContext = args.sourceArgs.toConnectionContext();
        this.httpClient = new SolrHttpClient(connectionContext);
        this.mode = CreateSnapshot.getSnapshotMode(args);
    }

    private boolean isCloud() {
        if (cloudTopology == null) {
            throw new IllegalStateException("Solr topology read before it was resolved");
        }
        return cloudTopology;
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

    /** Log/message wording; tolerates topology not being resolved yet (CREATE infers it later). */
    private String topologyLabel() {
        if (cloudTopology == null) {
            return COLLECTION_LABEL + "/" + CORE_LABEL;
        }
        return cloudTopology ? COLLECTION_LABEL : CORE_LABEL;
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
     * Thrown when IMPORT mode cannot confirm snapshot data exists at the configured location. IMPORT
     * imports an existing snapshot rather than creating one, so an empty or unlistable location is
     * fatal — continuing would migrate nothing or fail later with a more confusing error.
     */
    public static class SolrImportSnapshotUnavailable extends RuntimeException {
        public SolrImportSnapshotUnavailable(String message) {
            super(message);
        }

        public SolrImportSnapshotUnavailable(String message, Throwable cause) {
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

        cloudTopology = parseTopologyOption(args.solrTopology);

        if (mode == SnapshotMode.IMPORT) {
            // Before anything writes: schema staging puts files under the snapshot location, so a
            // later check could not tell a real backup from one this run had just created.
            validateSnapshotAccessible(parsedUri);
            if (cloudTopology == null) {
                cloudTopology = inferTopologyFromArtifact(parsedUri);
            }
        }
        resolveCollections(solrUrl);

        if (mode == SnapshotMode.CREATE) {
            // Topology stays unresolved: the backup request reveals it (see runCreateMode).
            runCreateMode(solrUrl, backupLocation, parsedUri);
            return;
        }

        if (cloudTopology == null) {
            // Rather than spend a Collections API request purely to detect — the one thing that
            // would drag 'collection-admin-read' back in — ask for the answer.
            throw new ParameterException(
                "Could not determine whether the backup at " + parsedUri.rawUri()
                    + " came from SolrCloud or standalone Solr: it contains none of the layout markers"
                    + " that identify either. Specify --solr-topology cloud|standalone.");
        }
        logResolvedTopology();
        if (!isCloud()) {
            ensureStandaloneConfigFiles(solrUrl, parsedUri);
        }
        runImportMode(solrUrl, parsedUri);
    }

    /**
     * Stages the schema into the synthetic {@code zk_backup_0/configs/} the RFS reader expects,
     * since standalone backups carry only index data. Never run for SolrCloud: its BACKUP writes
     * the real zk_backup two levels down, which a shallow synthetic one would shadow.
     */
    private void ensureStandaloneConfigFiles(String solrUrl, RepoUri parsedUri) {
        ensureConfigFilesInS3(solrUrl, parsedUri);
        ensureConfigFilesOnFilesystem(solrUrl, parsedUri);
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
        if (isCloud()) {
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
        if (!isCloud() && configFile.contains("schema")) {
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
        log.info("CREATE mode: backing up {} collection/core(s) to {}",
            args.solrCollections.size(), backupLocation);

        // No detection request: attempt the Collections API BACKUP we'd have to issue anyway. A
        // standalone Solr rejects it with "not running in SolrCloud mode", and that rejection is
        // the signal to switch. Nothing is written before the rejection, so the fallback is clean.
        if (cloudTopology == null || isCloud()) {
            var explicitlyCloud = cloudTopology != null;
            try {
                runCloudBackup(solrUrl, backupLocation, parsedUri);
                cloudTopology = Boolean.TRUE;
                logResolvedTopology();
                return;
            } catch (SolrSnapshotCreator.SolrBackupFailed | SolrHttpClient.SolrRequestException e) {
                // Standalone's rejection arrives as HTTP 400, so it lands on either type.
                // Only a positive match redirects; auth and repo errors surface as-is.
                if (explicitlyCloud || !isNotSolrCloudRejection(e.getMessage())) {
                    throw e;
                }
                log.info("Source is not running in SolrCloud mode — using the standalone replication handler");
            }
        }

        cloudTopology = Boolean.FALSE;
        logResolvedTopology();
        ensureStandaloneConfigFiles(solrUrl, parsedUri);
        runStandaloneBackup(solrUrl, backupLocation, parsedUri);
    }

    private void logResolvedTopology() {
        log.info("Running SolrBackupStrategy: mode={}, topology={}",
            mode, isCloud() ? "SolrCloud" : "standalone");
    }

    /** Solr's standalone rejection of the Collections API — the signal to use the replication handler. */
    static boolean isNotSolrCloudRejection(String message) {
        return message != null && message.toLowerCase(Locale.ROOT).contains("not running in solrcloud");
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
        log.info("IMPORT mode: verifying snapshot location accessibility for {} collection(s) at {}",
            args.solrCollections.size(), parsedUri.rawUri());
        log.info("IMPORT mode complete: config files ensured, snapshot location verified at {}",
            parsedUri.rawUri());
    }

    /** Fails fatally if the snapshot location is missing, empty, or unlistable. */
    private void validateSnapshotAccessible(RepoUri parsedUri) {
        if (parsedUri instanceof RepoUri.S3RepoUri s3RepoUri) {
            validateS3SnapshotAccessible(s3RepoUri.s3Uri());
        } else if (parsedUri instanceof RepoUri.FileRepoUri fileRepoUri) {
            validateFileSystemSnapshotAccessible(fileRepoUri.path());
        }
    }

    /** Fails fatally in IMPORT mode if the S3 snapshot location is empty or cannot be listed. */
    private void validateS3SnapshotAccessible(S3Uri repoUri) {
        var snapshotPrefix = computeParentPrefix(repoUri.key) + args.snapshotName + "/";
        try (var s3Client = buildS3Client(args.s3Region, args.endpoint)) {
            var empty = isS3SnapshotEmpty(s3Client, repoUri.bucketName, snapshotPrefix);
            requireNonEmptyS3Snapshot(repoUri.bucketName, snapshotPrefix, empty);
        }
    }

    /** Lists the snapshot prefix and reports whether it is empty; wraps listing failures fatally. */
    static boolean isS3SnapshotEmpty(S3Client s3Client, String bucketName, String snapshotPrefix) {
        try {
            var response = s3Client.listObjectsV2(
                software.amazon.awssdk.services.s3.model.ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(snapshotPrefix)
                    .maxKeys(1)
                    .build());
            return response.contents().isEmpty();
        } catch (Exception e) {
            throw new SolrImportSnapshotUnavailable(String.format(
                "IMPORT mode could not verify the snapshot location s3://%s/%s: %s. "
                    + "The snapshot data must already exist at this location before import.",
                bucketName, snapshotPrefix, e.getMessage()), e);
        }
    }

    /** Throws if the listed S3 snapshot prefix is empty; the IO/listing is done by the caller. */
    static void requireNonEmptyS3Snapshot(String bucketName, String snapshotPrefix, boolean empty) {
        if (empty) {
            throw new SolrImportSnapshotUnavailable(String.format(
                "IMPORT mode found no snapshot data at s3://%s/%s. "
                    + "The snapshot must be created/uploaded to this location before import.",
                bucketName, snapshotPrefix));
        }
        log.info("Snapshot data confirmed at s3://{}/{}", bucketName, snapshotPrefix);
    }

    /** Fails fatally in IMPORT mode if the filesystem snapshot directory is missing. */
    private void validateFileSystemSnapshotAccessible(String repoPath) {
        requireFilesystemSnapshotPresent(Paths.get(repoPath, args.snapshotName));
    }

    /** Throws if the filesystem snapshot directory does not exist. */
    static void requireFilesystemSnapshotPresent(Path snapshotDir) {
        if (Files.exists(snapshotDir) && Files.isDirectory(snapshotDir)) {
            log.info("Snapshot directory confirmed at {}", snapshotDir);
        } else {
            throw new SolrImportSnapshotUnavailable(String.format(
                "IMPORT mode found no snapshot directory at %s. "
                    + "The snapshot must be placed at this location before import.", snapshotDir));
        }
    }

    // ---- Collection resolution (shared across modes) ----

    /**
     * Resolves the collection/core list. {@code --solr-collections} skips this entirely; discovery
     * is the only step needing an admin-read permission, as Solr offers no lighter way to enumerate.
     */
    private void resolveCollections(String solrUrl) {
        if (!args.solrCollections.isEmpty()) {
            return;
        }
        try {
            var discovered = discoverCollections(solrUrl, httpClient, cloudTopology);
            args.solrCollections = discovered.names();
            if (cloudTopology == null && discovered.topology() != SolrTopology.UNKNOWN) {
                cloudTopology = discovered.topology() == SolrTopology.SOLR_CLOUD;
            }
        } catch (SolrTopologyDetectionException | ParameterException e) {
            throw e;
        } catch (Exception e) {
            throw new ParameterException("Failed to discover Solr collections/cores: " + e.getMessage());
        }

        if (args.solrCollections.isEmpty()) {
            throw new ParameterException("No Solr collections or cores found at " + solrUrl
                + ". Specify --solr-collections explicitly.");
        }
        log.info("Auto-discovered {} Solr {}(s): {}",
            args.solrCollections.size(), topologyLabel(), args.solrCollections);
    }

    /** Parses {@code --solr-topology}; null when unset, so inference proceeds. */
    static Boolean parseTopologyOption(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "cloud":
                return Boolean.TRUE;
            case "standalone":
                return Boolean.FALSE;
            default:
                throw new ParameterException(
                    "Invalid --solr-topology '" + value + "'. Valid values are 'cloud' and 'standalone'.");
        }
    }

    /**
     * Classifies an existing backup from its own layout, with no request to Solr — the same
     * SolrCloud-only markers {@code SolrBackupLayout} uses when the snapshot is later read.
     *
     * @return TRUE for SolrCloud, FALSE for standalone, or null if the repo could not be inspected.
     */
    private Boolean inferTopologyFromArtifact(RepoUri parsedUri) {
        try {
            var names = parsedUri instanceof RepoUri.S3RepoUri s3RepoUri
                ? listS3SnapshotEntryNames(s3RepoUri.s3Uri())
                : listFilesystemSnapshotEntryNames(
                    Paths.get(((RepoUri.FileRepoUri) parsedUri).path(), args.snapshotName));
            if (names.stream().anyMatch(SolrBackupStrategy::hasCloudBackupMarker)) {
                log.info("Inferred SolrCloud topology from the backup layout — no Solr request needed");
                return Boolean.TRUE;
            }
            if (names.stream().anyMatch(SolrBackupStrategy::hasStandaloneBackupMarker)
                || hasSiblingStandaloneIndex(parsedUri)) {
                log.info("Inferred standalone topology from the backup layout — no Solr request needed");
                return Boolean.FALSE;
            }
            // Neither marker present. The listing is capped and its order is unspecified, so this
            // is "couldn't tell", not "not cloud"; the caller asks for --solr-topology.
            log.info("Backup layout identified neither topology; --solr-topology is required");
            return null;
        } catch (Exception e) {
            log.info("Could not read the backup layout to infer topology ({}); --solr-topology is required",
                e.getMessage());
            return null;
        }
    }

    /**
     * Markers sit one or two levels below the snapshot root, so every segment is checked.
     * Excludes {@code zk_backup*}, which {@link #ensureStandaloneConfigFiles} also synthesizes.
     */
    static boolean hasCloudBackupMarker(String relativePath) {
        for (var segment : relativePath.split("/")) {
            if (segment.equals("shard_backup_metadata")
                || (segment.startsWith("backup") && segment.endsWith(".properties"))) {
                return true;
            }
        }
        return false;
    }

    /** SolrCloud names its shard directories {@code snapshot.shard<N>}; standalone uses the backup name. */
    private static final Pattern CLOUD_SHARD_SNAPSHOT = Pattern.compile("snapshot\\.shard\\d+");

    /**
     * Positive evidence of a standalone replication backup: a {@code snapshot.<backupName>/} index
     * whose suffix is not one of SolrCloud's {@code snapshot.shard<N>} directories. A backup named
     * "shardN" matches neither test and stays unknown, which is the safe outcome.
     */
    static boolean hasStandaloneBackupMarker(String relativePath) {
        for (var segment : relativePath.split("/")) {
            if (segment.startsWith("snapshot.") && !CLOUD_SHARD_SNAPSHOT.matcher(segment).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The replication handler writes {@code snapshot.<name>/} beside the snapshot dir, not inside
     * it. Probed by exact name so a sibling backup can't decide this snapshot's topology.
     */
    private boolean hasSiblingStandaloneIndex(RepoUri parsedUri) {
        var sibling = "snapshot." + args.snapshotName;
        if (parsedUri instanceof RepoUri.S3RepoUri s3RepoUri) {
            var repoUri = s3RepoUri.s3Uri();
            var prefix = computeParentPrefix(repoUri.key) + sibling + "/";
            try (var s3Client = buildS3Client(args.s3Region, args.endpoint)) {
                return !s3Client.listObjectsV2(
                    software.amazon.awssdk.services.s3.model.ListObjectsV2Request.builder()
                        .bucket(repoUri.bucketName).prefix(prefix).maxKeys(1).build())
                    .contents().isEmpty();
            }
        }
        return Files.isDirectory(Paths.get(((RepoUri.FileRepoUri) parsedUri).path(), sibling));
    }

    /** Caps how much of the snapshot is listed when classifying it; markers appear near the top. */
    private static final int TOPOLOGY_SCAN_LIMIT = 1000;

    /** Snapshot-relative key paths under the S3 snapshot prefix, bounded by {@link #TOPOLOGY_SCAN_LIMIT}. */
    private List<String> listS3SnapshotEntryNames(S3Uri repoUri) {
        var snapshotPrefix = computeParentPrefix(repoUri.key) + args.snapshotName + "/";
        try (var s3Client = buildS3Client(args.s3Region, args.endpoint)) {
            var response = s3Client.listObjectsV2(
                software.amazon.awssdk.services.s3.model.ListObjectsV2Request.builder()
                    .bucket(repoUri.bucketName)
                    .prefix(snapshotPrefix)
                    .maxKeys(TOPOLOGY_SCAN_LIMIT)
                    .build());
            return response.contents().stream()
                .map(o -> o.key().substring(snapshotPrefix.length()))
                .collect(Collectors.toList());
        }
    }

    /** Snapshot-relative paths under a filesystem snapshot dir, bounded by {@link #TOPOLOGY_SCAN_LIMIT}. */
    private static List<String> listFilesystemSnapshotEntryNames(Path snapshotDir) throws IOException {
        try (var paths = Files.walk(snapshotDir, 4)) {
            return paths.filter(p -> !p.equals(snapshotDir))
                .limit(TOPOLOGY_SCAN_LIMIT)
                .map(p -> snapshotDir.relativize(p).toString())
                .collect(Collectors.toList());
        }
    }

    // ---- Cloud vs standalone detection ----

    public enum SolrTopology {
        SOLR_CLOUD,
        STANDALONE,
        /** Reachable, but the response does not identify either topology. */
        UNKNOWN
    }

    private static final String COLLECTIONS_PATH = "/solr/admin/collections";
    /** Solr's predefined permission guarding {@link #COLLECTIONS_PATH}; collection discovery needs it too. */
    private static final String COLLECTIONS_PERMISSION = "collection-admin-read";
    /** Standalone's rejection reason, verbatim across Solr 6.6–9.8: "not running in SolrCloud mode". */
    private static final String NOT_CLOUD_MARKER = "not running in solrcloud";
    private static final int HTTP_OK = 200;
    /** The status Solr returns when rejecting the Collections API on a standalone instance. */
    private static final int HTTP_STANDALONE_REJECTION = 400;

    /**
     * Detect topology from the Collections API's response body. Uses the same endpoint as collection
     * discovery so detection never needs a permission the migration doesn't already require.
     */
    static SolrTopology detectTopology(String solrUrl, SolrHttpClient httpClient) {
        return classifyCollectionsResponse(fetchCollectionsList(solrUrl, httpClient), solrUrl);
    }

    /** GETs {@code /admin/collections?action=LIST}; failure to get any response is fatal, not a guess. */
    private static HttpResponse<String> fetchCollectionsList(String solrUrl, SolrHttpClient httpClient) {
        var listUrl = solrUrl + COLLECTIONS_PATH + "?action=LIST&wt=json";
        try {
            return httpClient.getRaw(listUrl, DETECTION_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SolrTopologyDetectionException(
                "Interrupted while detecting Solr topology at " + solrUrl, e);
        } catch (IOException e) {
            throw new SolrTopologyDetectionException(
                "Could not reach Solr at " + solrUrl + " to detect SolrCloud vs standalone topology: "
                    + e.getMessage(), e);
        }
    }

    /** Classifies a Collections API LIST response. Auth failures throw — they say nothing about topology. */
    private static SolrTopology classifyCollectionsResponse(HttpResponse<String> response, String solrUrl) {
        int status = response.statusCode();
        if (status == 401) {
            throw new SolrTopologyDetectionException(
                "Solr authentication failed (HTTP 401) while detecting topology at " + solrUrl
                    + "; cannot determine SolrCloud vs standalone. Check the source credentials.");
        }
        if (status == 403) {
            // Authenticated but not permitted: name the permission so the failure is self-diagnosing.
            throw new SolrTopologyDetectionException(
                "Solr authorization failed (HTTP 403) while detecting topology at " + solrUrl
                    + "; cannot determine SolrCloud vs standalone. Reading " + COLLECTIONS_PATH
                    + " requires the '" + COLLECTIONS_PERMISSION + "' permission — grant it to the source user.");
        }

        var body = parseOrNull(response.body());
        if (body != null) {
            // Status and body must agree. A collections array inside a 5xx (cached, proxied, or
            // wrapped response) is not SolrCloud answering LIST, and must not be read as one.
            if (status == HTTP_OK && body.path("collections").isArray()) {
                return SolrTopology.SOLR_CLOUD;
            }
            if (status == HTTP_STANDALONE_REJECTION
                && body.path("error").path("msg").asText("").toLowerCase(Locale.ROOT).contains(NOT_CLOUD_MARKER)) {
                return SolrTopology.STANDALONE;
            }
        }
        // Reachable but uninformative (proxy error page, 5xx, unrecognized body): do not assume standalone.
        log.atInfo().setMessage("Solr topology detection: HTTP {} from {} did not identify a topology")
            .addArgument(status).addArgument(solrUrl).log();
        return SolrTopology.UNKNOWN;
    }

    private static JsonNode parseOrNull(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            log.atInfo().setMessage("Could not parse Solr Collections API response: {}")
                .addArgument(e.getMessage()).log();
            return null;
        }
    }

    static boolean isSolrCloud(String solrUrl, SolrHttpClient httpClient) {
        var topology = detectTopology(solrUrl, httpClient);
        if (topology == SolrTopology.UNKNOWN) {
            throw new SolrTopologyDetectionException(
                "Could not determine SolrCloud vs standalone topology at " + solrUrl
                    + "; the Collections API response identified neither SolrCloud nor standalone");
        }
        return topology == SolrTopology.SOLR_CLOUD;
    }

    // ---- Collection / core discovery ----

    /** Discovered names plus whatever the request revealed about topology. */
    record Discovery(List<String> names, SolrTopology topology) {}

    /**
     * Discovers collections/cores and classifies the topology from the same request.
     *
     * @param knownTopology established topology, or null. Standalone skips the Collections API.
     */
    static Discovery discoverCollections(String solrUrl, SolrHttpClient httpClient, Boolean knownTopology)
            throws IOException {
        if (Boolean.FALSE.equals(knownTopology)) {
            return discoverFromCores(solrUrl, httpClient, SolrTopology.STANDALONE);
        }

        var response = fetchCollectionsList(solrUrl, httpClient);
        var topology = classifyCollectionsResponse(response, solrUrl);

        if (topology == SolrTopology.SOLR_CLOUD) {
            return new Discovery(collectionNames(response.body()), SolrTopology.SOLR_CLOUD);
        }
        if (Boolean.TRUE.equals(knownTopology)) {
            throw new ParameterException("Could not list SolrCloud collections at " + solrUrl
                + ". Specify --solr-collections explicitly.");
        }
        if (topology != SolrTopology.STANDALONE) {
            // Ambiguous. A SolrCloud node answers Core Admin STATUS with replica core names, so
            // mining it here would migrate those as if they were collections.
            throw new SolrTopologyDetectionException(
                "Could not determine SolrCloud vs standalone topology at " + solrUrl
                    + "; the Collections API response identified neither SolrCloud nor standalone");
        }
        return discoverFromCores(solrUrl, httpClient, SolrTopology.STANDALONE);
    }

    /**
     * Discovers core names. Only reached when the topology is already known to be standalone, so a
     * denied Collections API never redirects a SolrCloud source here — that misclassification is
     * what silently corrupts a snapshot.
     */
    private static Discovery discoverFromCores(String solrUrl, SolrHttpClient httpClient,
            SolrTopology topology) throws IOException {
        var json = httpClient.getString(
            solrUrl + "/solr/admin/cores?action=STATUS&wt=json", Duration.ofSeconds(10));
        return new Discovery(objectFieldKeys(MAPPER.readTree(json), "status"), topology);
    }

    private static List<String> collectionNames(String body) {
        var names = new ArrayList<String>();
        var node = parseOrNull(body);
        if (node != null && node.path("collections").isArray()) {
            node.path("collections").forEach(n -> names.add(n.asText()));
        }
        return names;
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
