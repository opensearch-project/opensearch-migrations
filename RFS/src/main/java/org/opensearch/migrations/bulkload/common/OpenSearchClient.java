package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.opensearch.migrations.AwarenessAttributeSettings;
import org.opensearch.migrations.Flavor;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationConverter;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.common.bulk.metadata.VersionControlMetadata;
import org.opensearch.migrations.bulkload.common.bulk.operations.IndexOperationMeta;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.pipeline.BatchSizePredicate;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.parsing.BulkResponseParser;
import org.opensearch.migrations.parsing.BulkResponseParser.ItemFailure;
import org.opensearch.migrations.parsing.BulkResponseParser.ItemPartition;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamRecord;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamSink;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Slf4j
public abstract class OpenSearchClient {
    protected static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createDefaultMapper();
    private static final int DEFAULT_MAX_RETRY_ATTEMPTS = 3;
    private static final Duration DEFAULT_BACKOFF = Duration.ofSeconds(1);
    private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(10);
    private static final Retry SNAPSHOT_RETRY_STRATEGY = Retry.backoff(DEFAULT_MAX_RETRY_ATTEMPTS, DEFAULT_BACKOFF)
        .maxBackoff(DEFAULT_MAX_BACKOFF);
    public static final Retry CHECK_IF_ITEM_EXISTS_RETRY_STRATEGY =
        Retry.backoff(DEFAULT_MAX_RETRY_ATTEMPTS, DEFAULT_BACKOFF)
            .maxBackoff(DEFAULT_MAX_BACKOFF);
    private static final Retry CREATE_ITEM_EXISTS_RETRY_STRATEGY =
        Retry.backoff(DEFAULT_MAX_RETRY_ATTEMPTS, DEFAULT_BACKOFF)
            .maxBackoff(DEFAULT_MAX_BACKOFF)
            .filter(throwable -> !(throwable instanceof InvalidResponse)); // Do not retry on this exception

    private static final int BULK_MAX_RETRY_ATTEMPTS = 15;
    private static final Duration BULK_BACKOFF = Duration.ofSeconds(2);
    private static final Duration BULK_MAX_BACKOFF = Duration.ofSeconds(60);
    /** Retries for up 10 minutes */
    private static final Retry BULK_RETRY_STRATEGY = Retry.backoff(BULK_MAX_RETRY_ATTEMPTS, BULK_BACKOFF)
        .maxBackoff(BULK_MAX_BACKOFF);
    public static final int BULK_TRUNCATED_RESPONSE_MAX_LENGTH = 1500;

    /**
     * Logs every bulk attempt at TRACE: the NDJSON request (before gzip) and the response, or the transport error.
     * Its output contains document contents. Configured on its own (BULK_REQUEST_TRACE_LOG_LEVEL) so it can be
     * turned on without the root logger.
     */
    public static final String BULK_REQUEST_TRACE_LOGGER = "BulkRequestTrace";
    private static final Logger BULK_TRACE = LoggerFactory.getLogger(BULK_REQUEST_TRACE_LOGGER);
    /** Request and response bodies in the trace are cut to this many bytes (0: no cut); BULK_REQUEST_TRACE_MAX_BYTES. */
    static final int BULK_TRACE_MAX_BYTES = parseTraceMaxBytes(System.getenv("BULK_REQUEST_TRACE_MAX_BYTES"));
    // Counts bodies formatted for the trace, so tests can show nothing is formatted while tracing is off.
    static final AtomicLong bulkTraceBodiesFormatted = new AtomicLong();
    public static final String SNAPSHOT_PREFIX_STR = "_snapshot/";

    protected final RestClient client;
    protected final FailedRequestsLogger failedRequestsLogger;
    private final Version version;
    private final CompressionMode compressionMode;
    private final int maxDocsPerBulkRequest;
    private final long maxBytesPerBulkRequest;

    // failed document stream context — null when no failed document stream is configured. The bootstrap in RfsMigrateDocuments
    // calls setFailedDocumentStreamContext(...) to install an S3FailedDocumentStreamSink when a bucket is provided.
    // volatile: emitFailedDocumentStreamRecord runs on concurrent bulk-write threads (batchConcurrency defaults
    // to 10) while setFailedDocumentStreamContext/setFailedDocumentStreamWorkItem are written from the pipeline thread, so these
    // need safe publication across threads.
    private volatile FailedDocumentStreamSink failedDocumentStreamSink;
    private volatile String failedDocumentStreamSessionId = "unknown-session";
    private volatile String failedDocumentStreamWorkerId = "unknown-worker";
    // Canonical work-item id (index + shard + checkpoint) for the work item currently being
    // processed. Updated per work item via setFailedDocumentStreamWorkItem; null until the first work item starts.
    private volatile String failedDocumentStreamWorkItemId;
    // Count of bulk responses with a 2xx status. A batch that keeps failing compares this against the value when it
    // started to tell "this batch can't be written" apart from "the target isn't accepting anything".
    private final AtomicLong acceptedBulkResponses = new AtomicLong();
    // Operations sent alone because they exceed maxBytesPerBulkRequest; the WARN for them is rate-limited.
    private final AtomicLong oversizedOperations = new AtomicLong();
    private final AtomicLong oversizedOperationsAtLastWarning = new AtomicLong();
    private final AtomicLong lastOversizedWarningNanos = new AtomicLong();
    static final Duration OVERSIZED_OPERATION_WARNING_INTERVAL = Duration.ofMinutes(1);
    // Documents bigger than this are not sent (0 = no limit). See rejectOversizedDocument.
    private volatile long maxDocumentSizeBytes;
    /** failureType for documents not sent because they exceed {@code maxDocumentSizeBytes}. */
    public static final String DOCUMENT_TOO_LARGE = "document_too_large";
    /** {@code measured} value for the encoded document body checked against {@code maxDocumentSizeBytes}. */
    public static final String MEASURED_REQUEST_BODY = "requestBody";

    protected OpenSearchClient(RestClient client, FailedRequestsLogger failedRequestsLogger, Version version,
                               CompressionMode compressionMode, int maxDocsPerBulkRequest, long maxBytesPerBulkRequest) {
        if (maxDocsPerBulkRequest < 1 || maxBytesPerBulkRequest < 1) {
            throw new IllegalArgumentException("Bulk request count and byte limits must be positive");
        }
        this.client = client;
        this.failedRequestsLogger = failedRequestsLogger;
        this.version = version;
        this.compressionMode = compressionMode;
        this.maxDocsPerBulkRequest = maxDocsPerBulkRequest;
        this.maxBytesPerBulkRequest = maxBytesPerBulkRequest;
    }

    /**
     * Install the failed document stream sink and identifiers used to tag records. Called by the bootstrap
     * once the session/worker identifiers are known. Safe to call before any bulk
     * requests are issued.
     */
    public void setFailedDocumentStreamContext(FailedDocumentStreamSink failedDocumentStreamSink, String sessionId, String workerId) {
        this.failedDocumentStreamSink = failedDocumentStreamSink;
        this.failedDocumentStreamSessionId = sessionId;
        this.failedDocumentStreamWorkerId = workerId;
    }

    /**
     * Update the work-coordination work item id stamped on subsequent failed document stream records. The target
     * client is long-lived and processes many work items over its lifetime, so the bootstrap
     * calls this at the start of each work item. The id is the canonical
     * {@code IWorkCoordinator.WorkItemAndDuration.WorkItem#toString()} form
     * ({@code base64url(index)__shard__startingDocId}), so a failed document stream record round-trips back to the
     * exact work item that produced it.
     */
    public void setFailedDocumentStreamWorkItem(String workItemId) {
        this.failedDocumentStreamWorkItemId = workItemId;
    }

    public FailedDocumentStreamSink getFailedDocumentStreamSink() {
        return failedDocumentStreamSink;
    }

    /**
     * Documents bigger than this many bytes are not sent to the target; they are recorded as
     * {@link FailureClass#NON_RETRYABLE} {@value #DOCUMENT_TOO_LARGE} failures instead. 0 means no limit.
     */
    public void setMaxDocumentSizeBytes(long maxDocumentSizeBytes) {
        if (maxDocumentSizeBytes < 0) {
            throw new IllegalArgumentException("maxDocumentSizeBytes must be >= 0, got " + maxDocumentSizeBytes);
        }
        this.maxDocumentSizeBytes = maxDocumentSizeBytes;
    }

    public long getMaxDocumentSizeBytes() {
        return maxDocumentSizeBytes;
    }

    /**
     * Record a document that won't be sent because it is bigger than {@link #getMaxDocumentSizeBytes()}: one
     * {@link FailureClass#NON_RETRYABLE} {@value #DOCUMENT_TOO_LARGE} failed document stream record (with the
     * size and limit, and the document itself so it can be replayed), a WARN naming the document but not its
     * contents, and the rejected-for-size and failed-document counters.
     *
     * @param measured {@value #MEASURED_REQUEST_BODY}, the encoded document body after transformation, or the
     *                 original document body when no transformation is configured. It excludes bulk action metadata
     *                 and NDJSON separators.
     */
    public void rejectOversizedDocument(String indexName, BulkOperationSpec op, long size, String measured,
                                        IRfsContexts.IRequestContext context) {
        var limit = maxDocumentSizeBytes;
        var docId = extractDocumentId(op);
        log.atWarn()
            .setMessage("Not sending document '{}' for index '{}': its {} is {} bytes, over maxDocumentSizeBytes {}")
            .addArgument(docId)
            .addArgument(indexName)
            .addArgument(measured)
            .addArgument(size)
            .addArgument(limit)
            .log();
        if (failedDocumentStreamSink == null) {
            log.atError()
                .setMessage("Document '{}' for index '{}' was skipped for its size and, with no failed document "
                    + "stream configured, is not recorded anywhere")
                .addArgument(docId)
                .addArgument(indexName)
                .log();
        }
        var detail = OBJECT_MAPPER.createObjectNode()
            .put("size", size)
            .put("limit", limit)
            .put("measured", measured);
        emitFailedDocumentStreamRecord(indexName, op, new ItemFailure(0, docId, DOCUMENT_TOO_LARGE, detail.toString()),
            FailureClass.NON_RETRYABLE);
        if (context != null) {
            context.addDocumentsRejectedForSize(1);
        }
        countFailedDocuments(context, FailureClass.NON_RETRYABLE, 1);
    }

    private boolean rejectIfTooLarge(String indexName, PreparedOperation op, IRfsContexts.IRequestContext context) {
        var limit = maxDocumentSizeBytes;
        var body = op.encoded().body();
        if (limit <= 0 || body == null || body.length <= limit) {
            return false;
        }
        rejectOversizedDocument(indexName, op.operation().get(), body.length, MEASURED_REQUEST_BODY, context);
        return true;
    }

    public Version getClusterVersion() {
        return version;
    }

    private JsonNode getSettingFromPersistentOrDefaults(String path, ObjectNode settings) {
        return settings.get("persistent").has(path) ?
            settings.get("persistent").get(path) : settings.get("defaults").get(path);
    }

    public AwarenessAttributeSettings getAwarenessAttributeSettings() {
        // OpenSearch Serverless does not support _cluster/settings API, return default disabled settings
        if (version.getFlavor() == Flavor.AMAZON_SERVERLESS_OPENSEARCH) {
            log.info("Serverless target detected, skipping awareness attribute settings (not supported)");
            return new AwarenessAttributeSettings(false, 0);
        }

        String settingsPath = "_cluster/settings?flat_settings&include_defaults";
        log.info("Starting getAwarenessAttributeSettings call to path={}", settingsPath);
        long startTime = System.currentTimeMillis();
        var getResponse = client.getAsync(settingsPath, null)
            .flatMap(resp -> {
                if (resp.statusCode == HttpURLConnection.HTTP_OK)
                {
                    return Mono.just(resp);
                } else {
                    String errorMessage = "Could not retrieve cluster settings: " + settingsPath + ". " + getString(resp);
                    return Mono.error(new OperationFailed(errorMessage, resp));
                }
            })
            .doOnError(e -> log.error(e.getMessage()))
            .retryWhen(CHECK_IF_ITEM_EXISTS_RETRY_STRATEGY)
            .block();
        long duration = System.currentTimeMillis() - startTime;
        log.info("Completed getAwarenessAttributeSettings in {} ms with statusCode={}",
                    duration,
                    getResponse != null ? getResponse.statusCode : "null");
        assert getResponse != null : ("getResponse should not be null; it should either be a valid response or " +
            "an exception should have been thrown.");
        ObjectNode settings;

        String balanceIsEnabledSetting = "cluster.routing.allocation.awareness.balance";

        try {
            settings = OBJECT_MAPPER.readValue(getResponse.body, ObjectNode.class);
        } catch (Exception e) {
            throw new OperationFailed("Could not parse settings values", getResponse);
        }
        boolean balanceIsEnabled = Optional.ofNullable(getSettingFromPersistentOrDefaults(balanceIsEnabledSetting, settings))
            .map(JsonNode::asBoolean)
            .orElse(false);

        if (!balanceIsEnabled) {
            return new org.opensearch.migrations.AwarenessAttributeSettings(false, 0);
        }
        AtomicInteger attributeValues = new AtomicInteger(1);

        String balanceAttributeSetting = "cluster.routing.allocation.awareness.attributes";
        String balanceAttributeValues = "cluster.routing.allocation.awareness.force.";

        Optional.ofNullable(getSettingFromPersistentOrDefaults(balanceAttributeSetting, settings))
            .ifPresent(attributes -> {
                attributes.forEach(attributeName -> {
                    Optional.ofNullable(getSettingFromPersistentOrDefaults(
                            balanceAttributeValues + attributeName.asText() + ".values", settings))
                        .map(JsonNode::asText)
                        .map(text -> text.split(","))
                        .ifPresent(values -> attributeValues.getAndAccumulate(
                            values.length,
                            Math::max
                        ));
                });
            });
        return new AwarenessAttributeSettings(true, attributeValues.get());
    }

    /*
     * Create a legacy template if it does not already exist.  Returns an Optional; if the template was created, it
     * will be the created object and empty otherwise.
     */
    public Optional<ObjectNode> createLegacyTemplate(
        String templateName,
        ObjectNode settings,
        IRfsContexts.ICheckedIdempotentPutRequestContext context
    ) {
        String targetPath = "_template/" + templateName;
        return createObjectIdempotent(targetPath, settings, context);
    }

    /*
     * Create a component template if it does not already exist.  Returns an Optional; if the template was created, it
     * will be the created object and empty otherwise.
     */
    public Optional<ObjectNode> createComponentTemplate(
        String templateName,
        ObjectNode settings,
        IRfsContexts.ICheckedIdempotentPutRequestContext context
    ) {
        String targetPath = "_component_template/" + templateName;
        return createObjectIdempotent(targetPath, settings, context);
    }

    /*
     * Create an index template if it does not already exist.  Returns an Optional; if the template was created, it
     * will be the created object and empty otherwise.
     */
    public Optional<ObjectNode> createIndexTemplate(
        String templateName,
        ObjectNode settings,
        IRfsContexts.ICheckedIdempotentPutRequestContext context
    ) {
        String targetPath = "_index_template/" + templateName;
        return createObjectIdempotent(targetPath, settings, context);
    }

    /** Returns true if this template already exists */
    public boolean hasLegacyTemplate(String templateName) {
        var targetPath = "_template/" + templateName;
        return hasObjectCheck(targetPath, null);
    }

    /** Returns true if this template already exists */
    public boolean hasComponentTemplate(String templateName) {
        var targetPath = "_component_template/" + templateName;
        return hasObjectCheck(targetPath, null);
    }

    /** Returns true if this template already exists */
    public boolean hasIndexTemplate(String templateName) {
        var targetPath = "_index_template/" + templateName;
        return hasObjectCheck(targetPath, null);
    }

    /** Returns true if this index already exists */
    public boolean hasIndex(String indexName) {
        return hasObjectCheck(indexName, null);
    }

    protected abstract String getCreateIndexPath(String indexName);

    /*
     * Create an index if it does not already exist.  Returns an Optional; if the index was created, it
     * will be the created object and empty otherwise.
     */
    public Optional<ObjectNode> createIndex(
        String indexName,
        ObjectNode settings,
        IRfsContexts.ICheckedIdempotentPutRequestContext context
    ) {
        var targetPath = getCreateIndexPath(indexName);
        return createObjectIdempotent(targetPath, settings, context);
    }

    private Optional<ObjectNode> createObjectIdempotent(
        String objectPath,
        ObjectNode settings,
        IRfsContexts.ICheckedIdempotentPutRequestContext context
    ) {
        log.info("Starting createObjectIdempotent for path={} with settings={}", objectPath, settings);
        var objectDoesNotExist = !hasObjectCheck(objectPath, context);
        if (objectDoesNotExist) {
            long startTime = System.currentTimeMillis();
            var putRequestContext = context == null ? null : context.createCheckRequestContext();
            var putResponse = client.putAsync(objectPath, settings.toString(), putRequestContext).flatMap(resp -> {
                if (resp.statusCode == HttpURLConnection.HTTP_OK) {
                    return Mono.just(resp);
                } else if (resp.statusCode == HttpURLConnection.HTTP_BAD_REQUEST) {
                    return Mono.error(
                        new InvalidResponse("Create object failed for " + objectPath + "\r\n" + resp.body, resp)
                    );
                } else {
                    String errorMessage = "Could not create object: " + objectPath + ". " + getString(resp);
                    return Mono.error(new OperationFailed(errorMessage, resp));
                }
            })
                .doOnError(e -> log.error(e.getMessage()))
                .retryWhen(CREATE_ITEM_EXISTS_RETRY_STRATEGY)
                .block();
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("Completed createObjectIdempotent for path={} in {} ms with statusCode={}",
                        objectPath, duration, putResponse != null ? putResponse.statusCode : "null");

            return Optional.of(settings);
        } else {
            log.debug("Object at path {} already exists, not attempting to create.", objectPath);
        }
        // The only response code that can end up here is HTTP_OK, which means the object already existed
        return Optional.empty();
    }

    private static String getString(HttpResponse resp) {
        return "Response Code: "
            + resp.statusCode
            + ", Response Message: "
            + resp.statusText
            + ", Response Body: "
            + resp.body;
    }

    private boolean hasObjectCheck(
        String objectPath,
        IRfsContexts.ICheckedIdempotentPutRequestContext context
    ) {
        log.info("Starting hasObjectCheck for path={}", objectPath);
        long startTime = System.currentTimeMillis();
        var requestContext = Optional.ofNullable(context)
            .map(IRfsContexts.ICheckedIdempotentPutRequestContext::createCheckRequestContext)
            .orElse(null);
        var getResponse = client.getAsync(objectPath, requestContext)
            .flatMap(resp -> {
                if (resp.statusCode == HttpURLConnection.HTTP_NOT_FOUND ||
                    resp.statusCode == HttpURLConnection.HTTP_OK)
                {
                    return Mono.just(resp);
                } else {
                    String errorMessage = "Could not create object: " + objectPath + ". " + getString(resp);
                    return Mono.error(new OperationFailed(errorMessage, resp));
                }
            })
            .doOnError(e -> log.error(e.getMessage()))
            .retryWhen(CHECK_IF_ITEM_EXISTS_RETRY_STRATEGY)
            .block();

        long duration = System.currentTimeMillis() - startTime;
        log.info("Completed hasObjectCheck for path={} with status={} in {} ms",
                    objectPath, getResponse.statusCode, duration);
        assert getResponse != null : ("getResponse should not be null; it should either be a valid response or " +
            "an exception should have been thrown.");
        return getResponse.statusCode == HttpURLConnection.HTTP_OK;
    }

    /*
     * Attempts to register a snapshot repository; no-op if the repo already exists.
     */
    public void registerSnapshotRepo(
        String repoName,
        ObjectNode settings,
        IRfsContexts.ICreateSnapshotContext context
    ) {
        String targetPath = SNAPSHOT_PREFIX_STR + repoName;
        log.info("Starting registerSnapshotRepo for repoName={}", repoName);
        long startTime = System.currentTimeMillis();
        var putResponse = client.putAsync(targetPath, settings.toString(), context.createRegisterRequest()).flatMap(resp -> {
            if (resp.statusCode == HttpURLConnection.HTTP_OK) {
                return Mono.just(resp);
            } else {
                String errorMessage = "Could not register snapshot repo: " + targetPath + ". " + getString(resp);
                return Mono.error(new OperationFailed(errorMessage, resp));
            }
        })
            .doOnError(e -> log.error(e.getMessage()))
            .retryWhen(SNAPSHOT_RETRY_STRATEGY)
            .block();
        long duration = System.currentTimeMillis() - startTime;
        log.info("Completed registerSnapshotRepo for repoName={} in {} ms with statusCode={}",
                    repoName, duration, putResponse != null ? putResponse.statusCode : "null");
    }

    /*
     * Attempts to create a snapshot; no-op if the snapshot already exists.
     */
    public void createSnapshot(
        String repoName,
        String snapshotName,
        ObjectNode settings,
        IRfsContexts.ICreateSnapshotContext context
    ) {
        String targetPath = SNAPSHOT_PREFIX_STR + repoName + "/" + snapshotName;
        log.info("Starting createSnapshot for repoName={}, snapshotName={}", repoName, snapshotName);
        long startTime = System.currentTimeMillis();
        var putResponse = client.putAsync(targetPath, settings.toString(), context.createSnapshotContext()).flatMap(resp -> {
            if (resp.statusCode == HttpURLConnection.HTTP_OK) {
                return Mono.just(resp);
            } else {
                String errorMessage = "Could not create snapshot: " + targetPath + "." + getString(resp);
                return Mono.error(new OperationFailed(errorMessage, resp));
            }
        })
            .doOnError(e -> log.error(e.getMessage()))
            .retryWhen(SNAPSHOT_RETRY_STRATEGY)
            .block();
        long duration = System.currentTimeMillis() - startTime;
        log.info("Completed createSnapshot for repoName={}, snapshotName={} in {} ms with statusCode={}",
                    repoName, snapshotName, duration, putResponse != null ? putResponse.statusCode : "null");
    }

    /*
     * Get the status of a snapshot.  Returns an Optional; if the snapshot was found, it will be the snapshot status
     * and empty otherwise.
     */
    public Optional<ObjectNode> getSnapshotStatus(
        String repoName,
        String snapshotName,
        IRfsContexts.ICreateSnapshotContext context
    ) {
        String targetPath = SNAPSHOT_PREFIX_STR + repoName + "/" + snapshotName;
        log.info("Starting getSnapshotStatus for repoName={}, snapshotName={}", repoName, snapshotName);
        long startTime = System.currentTimeMillis();
        var getResponse = client.getAsync(targetPath, context.createGetSnapshotContext()).flatMap(resp -> {
            if (resp.statusCode == HttpURLConnection.HTTP_OK || resp.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                return Mono.just(resp);
            } else {
                String errorMessage = "Could get status of snapshot: "
                    + targetPath
                    + ". Response Code: "
                    + resp.statusCode
                    + ", Response Body: "
                    + resp.body;
                return Mono.error(new OperationFailed(errorMessage, resp));
            }
        })
            .doOnError(e -> log.error(e.getMessage()))
            .retryWhen(SNAPSHOT_RETRY_STRATEGY)
            .block();

        long duration = System.currentTimeMillis() - startTime;
        log.info("Completed getSnapshotStatus for repoName={}, snapshotName={} in {} ms with statusCode={}",
                    repoName, snapshotName, duration, getResponse != null ? getResponse.statusCode : "null");

        assert getResponse != null : ("getResponse should not be null; it should either be a valid response or an "
            + "exception should have been thrown.");
        if (getResponse.statusCode == HttpURLConnection.HTTP_OK) {
            try {
                return Optional.of(OBJECT_MAPPER.readValue(getResponse.body, ObjectNode.class));
            } catch (Exception e) {
                String errorMessage = "Could not parse response for: _snapshot/" + repoName + "/" + snapshotName;
                throw new OperationFailed(errorMessage, getResponse);
            }
        } else if (getResponse.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
            return Optional.empty();
        } else {
            String errorMessage = "Should not have gotten here while parsing response for: _snapshot/"
                + repoName
                + "/"
                + snapshotName;
            throw new OperationFailed(errorMessage, getResponse);
        }
    }

    protected abstract String getBulkRequestPath(String indexName);

    protected Retry getBulkRetryStrategy() {
        return BULK_RETRY_STRATEGY;
    }

    private BulkOperationSpec stripDocumentId(BulkOperationSpec original) {
        if (original instanceof IndexOp indexOp) {
            var metadata = indexOp.getOperation();
            var versioning = metadata.getVersioning();
            // Explicit versions require an ID. Keep unrelated concurrency checks
            // intact so invalid custom combinations are still rejected by the target.
            var unversioned = versioning != null
                && (versioning.getIfSeqNo() != null || versioning.getIfPrimaryTerm() != null)
                    ? VersionControlMetadata.builder()
                        .ifSeqNo(versioning.getIfSeqNo())
                        .ifPrimaryTerm(versioning.getIfPrimaryTerm())
                        .build()
                    : null;
            var newMetadata = IndexOperationMeta.builder()
                .index(metadata.getIndex())
                .type(metadata.getType())
                .routing(metadata.getRouting())
                .write(metadata.getWrite())
                .versioning(unversioned)
                .opType(metadata.getOpType())
                .build();
            
            return IndexOp.builder()
                .operation(newMetadata)
                .document(original.getRawDocument() == null ? original.getDocument() : null)
                .rawDocument(original.getRawDocument())
                .includeDocument(original.isIncludeDocument())
                .originalSource(original.getOriginalSourceBytes() == null ? original.getOriginalSource() : null)
                .originalSourceBytes(original.getOriginalSourceBytes())
                .sourceHints(original.getSourceHints())
                .sourceMetadata(original.getSourceMetadata())
                .build();
        }
        return original;
    }

    private static String truncateMessageIfNeeded(String input, int maxCharacters) {
        if (input == null || input.length() <= maxCharacters) {
            return input;
        }
        int partLength = maxCharacters / 2;
        String head = input.substring(0, partLength);
        String tail = input.substring(input.length() - partLength);
        return head + "... [truncated] ..." + tail;
    }

    public Mono<BulkResponse> sendBulkRequest(String indexName, List<? extends BulkOperationSpec> docs,
                                              IRfsContexts.IRequestContext context) {
        return sendBulkRequest(indexName, docs, context, false, DocumentExceptionAllowlist.empty());
    }

    public Mono<BulkResponse> sendBulkRequest(String indexName, List<? extends BulkOperationSpec> docs,
                                              IRfsContexts.IRequestContext context,
                                              boolean allowServerGeneratedIds,
                                              DocumentExceptionAllowlist allowlist)
    {
        return sendBulkRequests(indexName, docs, op -> new PreparedOperation(() -> op,
            BulkNdjson.encode(allowServerGeneratedIds ? stripDocumentId(op) : op, OBJECT_MAPPER)),
            TRANSFORMED_PATH, context, allowlist);
    }

    /** Send raw source bytes without a byte[]→Map→byte[] round-trip. */
    public Mono<BulkResponse> sendBulkRequestRaw(String indexName, List<Document> docs,
                                                  IRfsContexts.IRequestContext context,
                                                  boolean allowServerGeneratedIds,
                                                  DocumentExceptionAllowlist allowlist) {
        return sendBulkRequests(indexName, docs, doc -> new PreparedOperation(
            () -> BulkOperationConverter.fromRawDocument(doc, indexName),
            BulkNdjson.encodeRaw(doc, indexName, allowServerGeneratedIds, OBJECT_MAPPER)),
            RAW_PATH, context, allowlist);
    }

    /** Request path labels for oversized-operation reporting: source bytes as-is, or serialized operations. */
    static final String RAW_PATH = "raw";
    static final String TRANSFORMED_PATH = "transformed";

    // Build diagnostic operations only on failure; successful raw requests need only the encoded bytes.
    // Sonar misidentifies the generated record constructor as a method with unused parameters.
    @SuppressWarnings({"java:S100", "java:S1172"})
    private record PreparedOperation(Supplier<BulkOperationSpec> operation, BulkNdjson.EncodedOperation encoded) {}

    private <T> Mono<BulkResponse> sendBulkRequests(
        String indexName, List<T> docs, Function<T, PreparedOperation> prepare, String path,
        IRfsContexts.IRequestContext context, DocumentExceptionAllowlist allowlist
    ) {
        // The batching predicate and retry state must be fresh for every subscription.
        return Mono.defer(() -> {
            long acceptedBaseline = acceptedBulkResponses.get();
            return Flux.fromIterable(docs)
                .map(prepare)
                .filter(op -> !rejectIfTooLarge(indexName, op, context))
                .bufferUntil(new BatchSizePredicate<>(maxDocsPerBulkRequest, maxBytesPerBulkRequest,
                    (PreparedOperation op) -> op.encoded().size()), true)
                .doOnNext(batch -> noteOversizedOperation(indexName, batch, path, context))
                // A split batch completes in source order, before its source checkpoint can advance.
                .concatMap(batch -> executeBulkWithRetry(indexName, batch, context, allowlist, acceptedBaseline), 1)
                .reduce(OpenSearchClient::combine)
                .defaultIfEmpty(new BulkResponse(200, "OK", Map.of(), "{\"errors\":false,\"items\":[]}"));
        });
    }

    /**
     * Count, and WARN about (at most once per {@link #OVERSIZED_OPERATION_WARNING_INTERVAL}), an operation that is
     * bigger than the bulk byte limit and so is sent in a request by itself. Never logs document contents or ids.
     */
    private void noteOversizedOperation(String indexName, List<PreparedOperation> batch, String path,
                                        IRfsContexts.IRequestContext context) {
        if (batch.size() != 1) {
            return;
        }
        long size = batch.get(0).encoded().size();
        if (size <= maxBytesPerBulkRequest) {
            return;
        }
        long total = oversizedOperations.incrementAndGet();
        if (context != null) {
            context.addBulkOversizedOperation(path);
        }
        long now = System.nanoTime();
        long last = lastOversizedWarningNanos.get();
        boolean due = total == 1 || now - last >= OVERSIZED_OPERATION_WARNING_INTERVAL.toNanos();
        if (due && lastOversizedWarningNanos.compareAndSet(last, now)) {
            long sinceLastWarning = total - oversizedOperationsAtLastWarning.getAndSet(total);
            log.atWarn()
                .setMessage("Sending a {}-byte operation for index '{}' in a request by itself because it exceeds the "
                    + "{}-byte bulk request limit ({} path). {} such operations so far, {} since the last warning")
                .addArgument(size)
                .addArgument(indexName)
                .addArgument(maxBytesPerBulkRequest)
                .addArgument(path)
                .addArgument(total)
                .addArgument(sinceLastWarning)
                .log();
        }
    }

    private static BulkNdjson.SerializedBulk assemble(List<PreparedOperation> pending) {
        return BulkNdjson.assemble(pending, PreparedOperation::encoded);
    }

    /**
     * Shared bulk request execution with retry, error handling, and compaction.
     *
     * <p>Each attempt is classified by status code before any body parsing:
     * <ul>
     *   <li>200/201: per-item results are parsed; successes and non-retryable items leave {@code pendingOps},
     *       retryable items are retried. A body that can't be matched to the request retries everything.</li>
     *   <li>400/413: the request fails the same way every time. A multi-operation batch is halved to isolate
     *       the operation responsible; a single operation is a terminal {@link FailureClass#NON_RETRYABLE} failure.</li>
     *   <li>Any other status (429, 5xx, ...): the whole request is retried.</li>
     *   <li>No response (timeout, reset, premature close): retried, but a multi-operation batch is halved after
     *       {@value #NO_RESPONSE_ATTEMPTS_BEFORE_SPLIT} consecutive no-response attempts, and a single operation
     *       gives up after {@value #NO_RESPONSE_ATTEMPTS_BEFORE_EXHAUSTION}.</li>
     * </ul>
     *
     * <p>Terminal failures are resolved by {@link #finishWithTerminalFailure}.
     *
     * @param indexName         target index
     * @param pendingOps        mutable list of pending operations (compacted on partial success)
     * @param context           request context for metrics
     * @param allowlist         exception types to treat as success
     * @param acceptedBaseline  {@link #acceptedBulkResponses} when the caller's whole batch started, so a request
     *                          accepted for an earlier part of the same batch shows the target is up
     */
    private Mono<BulkResponse> executeBulkWithRetry(
        String indexName,
        List<PreparedOperation> pendingOps,
        IRfsContexts.IRequestContext context,
        DocumentExceptionAllowlist allowlist,
        long acceptedBaseline
    ) {
        var totals = new RequestTotals();
        totals.acceptedResponsesAtStart = acceptedBaseline;
        return Mono.defer(() -> {
            var request = assemble(pendingOps);
            var bodyBytes = request.bytes();
            if (totals.attempts == 0) {
                totals.target = request.operations();
            }
            totals.attempts++;
            totals.bytes += bodyBytes.length;
            long start = System.nanoTime();
            long attempt = totals.attempts;
            traceRequest(indexName, attempt, pendingOps.size(), bodyBytes);
            return postBulkRequest(indexName, bodyBytes, context)
                .doOnSuccess(response -> {
                    long elapsed = System.nanoTime() - start;
                    totals.durationNanos += elapsed;
                    traceResponse(indexName, attempt, response, elapsed);
                })
                .doOnError(error -> {
                    long elapsed = System.nanoTime() - start;
                    totals.durationNanos += elapsed;
                    traceTransportError(indexName, attempt, error, elapsed);
                })
                .onErrorMap(NoResponseException::new)
                .flatMap(response -> classifyResponse(indexName, pendingOps, totals, response, context, allowlist))
                .onErrorResume(NoResponseException.class, error -> classifyNoResponse(indexName, pendingOps, totals, error));
        })
        .retryWhen(getBulkRetryStrategy())
        .onErrorResume(error -> Mono.just(new AttemptOutcome.Exhausted(error)))
        .flatMap(outcome -> switch (outcome) {
            case AttemptOutcome.Completed completed -> Mono.just(withTotals(completed.response(), totals));
            case AttemptOutcome.Split split -> splitAndSend(indexName, pendingOps, context, allowlist, totals, split.reason());
            case AttemptOutcome.Rejected rejected -> finishWithTerminalFailure(indexName, pendingOps, totals, context,
                allowlist, FailureClass.NON_RETRYABLE,
                new OperationFailed(rejected.response().getFailureMessage(), rejected.response()));
            case AttemptOutcome.Exhausted exhausted -> finishWithTerminalFailure(indexName, pendingOps, totals, context,
                allowlist, FailureClass.RETRYABLE_EXHAUSTED, exhausted.error());
        });
    }

    static int parseTraceMaxBytes(String value) {
        if (value == null || value.isBlank()) {
            return 1024 * 1024;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            log.atWarn().setMessage("Ignoring BULK_REQUEST_TRACE_MAX_BYTES={}: not an integer").addArgument(value).log();
            return 1024 * 1024;
        }
    }

    // Each trace method checks the level first, so nothing is converted or copied while tracing is off.
    private static void traceRequest(String indexName, long attempt, int operations, byte[] body) {
        if (!BULK_TRACE.isTraceEnabled()) {
            return;
        }
        BULK_TRACE.atTrace()
            .setMessage("Bulk request attempt {} to index '{}': {} operations, {} bytes\n{}")
            .addArgument(attempt)
            .addArgument(indexName)
            .addArgument(operations)
            .addArgument(body.length)
            .addArgument(traceBody(body))
            .log();
    }

    private static void traceResponse(String indexName, long attempt, HttpResponse response, long elapsedNanos) {
        if (!BULK_TRACE.isTraceEnabled()) {
            return;
        }
        BULK_TRACE.atTrace()
            .setMessage("Bulk response to attempt {} for index '{}': status {} after {} ms\n{}")
            .addArgument(attempt)
            .addArgument(indexName)
            .addArgument(response.statusCode)
            .addArgument(Duration.ofNanos(elapsedNanos).toMillis())
            .addArgument(response.body == null ? null : traceBody(response.body.getBytes(StandardCharsets.UTF_8)))
            .log();
    }

    private static void traceTransportError(String indexName, long attempt, Throwable error, long elapsedNanos) {
        if (!BULK_TRACE.isTraceEnabled()) {
            return;
        }
        BULK_TRACE.atTrace()
            .setMessage("Bulk request attempt {} for index '{}' got no response after {} ms: {}: {}")
            .addArgument(attempt)
            .addArgument(indexName)
            .addArgument(Duration.ofNanos(elapsedNanos).toMillis())
            .addArgument(error.getClass().getName())
            .addArgument(error.getMessage())
            .log();
    }

    static String traceBody(byte[] body) {
        bulkTraceBodiesFormatted.incrementAndGet();
        int limit = BULK_TRACE_MAX_BYTES;
        if (limit == 0 || body.length <= limit) {
            return new String(body, StandardCharsets.UTF_8);
        }
        return new String(body, 0, limit, StandardCharsets.UTF_8)
            + "... [" + (body.length - limit) + " more bytes not logged]";
    }

    /** Consecutive no-response attempts after which a multi-operation batch is halved. */
    static final int NO_RESPONSE_ATTEMPTS_BEFORE_SPLIT = 2;
    /** Consecutive no-response attempts after which a single operation stops retrying. */
    static final int NO_RESPONSE_ATTEMPTS_BEFORE_EXHAUSTION = 4;

    /** The result of a bulk attempt that ends the retry loop; anything retryable is an error signal instead. */
    private sealed interface AttemptOutcome {
        /** Every pending operation succeeded, was allowlisted, or was routed to the failed document stream. */
        record Completed(BulkResponse response) implements AttemptOutcome {}
        /** Halve the batch and send each half on its own. */
        record Split(String reason) implements AttemptOutcome {}
        /** The target rejected a single-operation request outright (400/413). */
        record Rejected(BulkResponse response) implements AttemptOutcome {}
        /** Retries (or the no-response budget) ran out. */
        record Exhausted(Throwable error) implements AttemptOutcome {}
    }

    /** No HTTP response was received for a bulk attempt (timeout, connection reset, premature close). */
    public static class NoResponseException extends RuntimeException {
        public NoResponseException(Throwable cause) {
            super("No response to bulk request: " + cause, cause);
        }
    }

    private Mono<AttemptOutcome> classifyResponse(
        String indexName,
        List<PreparedOperation> pendingOps,
        RequestTotals totals,
        HttpResponse response,
        IRfsContexts.IRequestContext context,
        DocumentExceptionAllowlist allowlist
    ) {
        totals.consecutiveNoResponses = 0;
        var resp = new BulkResponse(response.statusCode, response.statusText, response.headers, response.body);
        if (!resp.hasBadStatusCode()) {
            acceptedBulkResponses.incrementAndGet();
            if (resp.reportsNoFailedOperations()) {
                return Mono.just(new AttemptOutcome.Completed(resp));
            }
            log.atInfo()
                .setMessage("Bulk response on index '{}' contains errors: {}")
                .addArgument(indexName)
                .addArgument(() -> truncateMessageIfNeeded(response.body, BULK_TRUNCATED_RESPONSE_MAX_LENGTH))
                .log();

            int successCount = compactPendingDocs(indexName, pendingOps, resp, context, allowlist);

            if (pendingOps.isEmpty()) {
                return Mono.just(new AttemptOutcome.Completed(resp));
            }
            log.atWarn()
                .setMessage("After bulk request attempt {} on index '{}', {} more documents have succeeded, {} remain. The error response message was: {}")
                .addArgument(totals.attempts)
                .addArgument(indexName)
                .addArgument(successCount)
                .addArgument(pendingOps::size)
                .addArgument(truncateMessageIfNeeded(response.body, BULK_TRUNCATED_RESPONSE_MAX_LENGTH))
                .log();
            return Mono.error(new OperationFailed(resp.getFailureMessage(), resp));
        }
        if (isNonRetryableRequestStatus(resp.statusCode)) {
            log.atWarn()
                .setMessage("Bulk request attempt {} on index '{}' with {} documents was rejected with status {}; {}. Response: {}")
                .addArgument(totals.attempts)
                .addArgument(indexName)
                .addArgument(pendingOps::size)
                .addArgument(resp.statusCode)
                .addArgument(pendingOps.size() > 1 ? "splitting the batch" : "not retrying")
                .addArgument(() -> truncateMessageIfNeeded(response.body, BULK_TRUNCATED_RESPONSE_MAX_LENGTH))
                .log();
            return Mono.just(pendingOps.size() > 1
                ? new AttemptOutcome.Split("status " + resp.statusCode)
                : new AttemptOutcome.Rejected(resp));
        }
        log.atWarn()
            .setMessage("Bulk request attempt {} on index '{}' failed with status {}; retrying all {} documents. Response: {}")
            .addArgument(totals.attempts)
            .addArgument(indexName)
            .addArgument(resp.statusCode)
            .addArgument(pendingOps::size)
            .addArgument(() -> truncateMessageIfNeeded(response.body, BULK_TRUNCATED_RESPONSE_MAX_LENGTH))
            .log();
        return Mono.error(new OperationFailed(resp.getFailureMessage(), resp));
    }

    /** 400 and 413 describe the request itself, so resending the same bytes can't succeed. */
    private static boolean isNonRetryableRequestStatus(int statusCode) {
        return statusCode == HttpURLConnection.HTTP_BAD_REQUEST || statusCode == HttpURLConnection.HTTP_ENTITY_TOO_LARGE;
    }

    private Mono<AttemptOutcome> classifyNoResponse(
        String indexName,
        List<PreparedOperation> pendingOps,
        RequestTotals totals,
        NoResponseException error
    ) {
        totals.consecutiveNoResponses++;
        boolean split = pendingOps.size() > 1 && totals.consecutiveNoResponses >= NO_RESPONSE_ATTEMPTS_BEFORE_SPLIT;
        boolean exhausted = pendingOps.size() == 1
            && totals.consecutiveNoResponses >= NO_RESPONSE_ATTEMPTS_BEFORE_EXHAUSTION;
        log.atWarn()
            .setMessage("Bulk request attempt {} on index '{}' with {} documents got no response ({} in a row): {}; {}")
            .addArgument(totals.attempts)
            .addArgument(indexName)
            .addArgument(pendingOps::size)
            .addArgument(totals.consecutiveNoResponses)
            .addArgument(() -> String.valueOf(error.getCause()))
            .addArgument(split ? "splitting the batch" : exhausted ? "giving up" : "retrying")
            .log();
        if (split) {
            return Mono.just(new AttemptOutcome.Split("no response"));
        }
        if (exhausted) {
            return Mono.just(new AttemptOutcome.Exhausted(error));
        }
        return Mono.error(error);
    }

    /**
     * Send each half of {@code pendingOps} as its own batch, in order, each with a fresh retry budget. The attempts
     * made before the split count as retries; the halves account for the batch's operations.
     */
    private Mono<BulkResponse> splitAndSend(
        String indexName,
        List<PreparedOperation> pendingOps,
        IRfsContexts.IRequestContext context,
        DocumentExceptionAllowlist allowlist,
        RequestTotals totals,
        String reason
    ) {
        int middle = pendingOps.size() / 2;
        var halves = List.<List<PreparedOperation>>of(
            new ArrayList<>(pendingOps.subList(0, middle)),
            new ArrayList<>(pendingOps.subList(middle, pendingOps.size())));
        log.atWarn()
            .setMessage("Splitting a bulk request on index '{}' of {} documents into {} and {} ({})")
            .addArgument(indexName)
            .addArgument(pendingOps::size)
            .addArgument(middle)
            .addArgument(pendingOps.size() - middle)
            .addArgument(reason)
            .log();
        var beforeSplit = new MigrationMetrics.Requests(0, totals.attempts, totals.attempts, totals.bytes,
            Duration.ofNanos(totals.durationNanos).toMillis());
        return Flux.fromIterable(halves)
            .concatMap(half -> executeBulkWithRetry(indexName, half, context, allowlist,
                totals.acceptedResponsesAtStart), 1)
            .reduce(OpenSearchClient::combine)
            .map(combined -> new BulkResponse(combined.statusCode, combined.statusText, combined.headers,
                combined.body, combined.target, beforeSplit.plus(combined.requests)));
    }

    private static BulkResponse combine(BulkResponse previous, BulkResponse next) {
        return new BulkResponse(next.statusCode, next.statusText, next.headers, next.body,
            previous.target.plus(next.target), previous.requests.plus(next.requests));
    }

    /**
     * Resolve operations that can't be written. When a failed document stream is configured, the operations are
     * recorded there and the batch completes, so the migration moves past them instead of failing the work item and
     * replaying the same documents forever. Without a stream there is nowhere to keep them, so the error propagates
     * and the work item fails, as before.
     *
     * <p>A transient failure (429, 5xx, no response) only counts as this batch's problem if the target accepted
     * some other bulk request since the caller's batch started (an earlier split, or a concurrent batch).
     * Otherwise the target is assumed to be unavailable and the error propagates, so an outage fails the work item
     * instead of filling the stream with every batch.
     */
    private Mono<BulkResponse> finishWithTerminalFailure(
        String indexName,
        List<PreparedOperation> pendingOps,
        RequestTotals totals,
        IRfsContexts.IRequestContext context,
        DocumentExceptionAllowlist allowlist,
        FailureClass failureClass,
        Throwable error
    ) {
        if (pendingOps.isEmpty()) {
            log.atError()
                .setCause(error)
                .setMessage("Unexpected empty document list for bulk request on index {}")
                .addArgument(indexName)
                .log();
            return Mono.error(error);
        }
        failedRequestsLogger.logBulkFailure(
            indexName,
            pendingOps::size,
            () -> new String(assemble(pendingOps).bytes(), StandardCharsets.UTF_8),
            error
        );
        boolean targetAccepting = failureClass == FailureClass.NON_RETRYABLE
            || acceptedBulkResponses.get() > totals.acceptedResponsesAtStart;
        var sink = failedDocumentStreamSink;
        if (sink == null || !targetAccepting) {
            if (sink != null) {
                log.atError()
                    .setMessage("Bulk request on index '{}' for {} documents failed after {} attempts and the target "
                        + "accepted no other bulk request meanwhile; failing the work item so it is retried later")
                    .addArgument(indexName)
                    .addArgument(pendingOps::size)
                    .addArgument(totals.attempts)
                    .log();
            }
            return Mono.error(error);
        }
        if (failureClass == FailureClass.NON_RETRYABLE) {
            var failure = wholeRequestFailure(findOperationFailed(error).map(f -> f.response).orElse(null), error);
            for (var op : pendingOps) {
                emitFailedDocumentStreamRecord(indexName, op.operation().get(), failure, failureClass);
            }
        } else {
            emitRetryExhaustedToFailedDocumentStream(indexName, pendingOps, error, allowlist);
        }
        countFailedDocuments(context, failureClass, pendingOps.size());
        log.atError()
            .setMessage("Giving up on {} documents for index '{}' after {} attempts ({}); they were written to the "
                + "failed document stream at {} and the migration continues")
            .addArgument(pendingOps::size)
            .addArgument(indexName)
            .addArgument(totals.attempts)
            .addArgument(failureClass)
            .addArgument(sink::getLocation)
            .log();
        var lastResponse = findOperationFailed(error).map(f -> f.response).orElse(null);
        var response = lastResponse != null
            ? new BulkResponse(lastResponse.statusCode, lastResponse.statusText, lastResponse.headers, lastResponse.body)
            : new BulkResponse(0, "No response", Map.of(), "");
        return Mono.just(withTotals(response, totals));
    }

    private static void countFailedDocuments(IRfsContexts.IRequestContext context, FailureClass failureClass, long count) {
        if (context != null && count > 0) {
            context.addBulkDocumentsFailed(failureClass.name(), count);
        }
    }

    private static BulkResponse withTotals(BulkResponse response, RequestTotals totals) {
        return new BulkResponse(response.statusCode, response.statusText, response.headers, response.body,
            totals.target,
            new MigrationMetrics.Requests(
                1, totals.attempts, Math.max(0, totals.attempts - 1),
                totals.bytes, Duration.ofNanos(totals.durationNanos).toMillis()));
    }

    private static final class RequestTotals {
        long attempts;
        long bytes;
        long durationNanos;
        int consecutiveNoResponses;
        long acceptedResponsesAtStart;
        MigrationMetrics.Operations target = MigrationMetrics.Operations.ZERO;
    }

    private Mono<HttpResponse> postBulkRequest(String indexName, byte[] bodyBytes,
                                                       IRfsContexts.IRequestContext context) {
        var additionalHeaders = new HashMap<String, List<String>>();
        if (CompressionMode.GZIP_BODY_COMPRESSION.equals(compressionMode)) {
            RestClient.addGzipRequestHeaders(additionalHeaders);
            RestClient.addGzipResponseHeaders(additionalHeaders);
        }
        return client.postAsyncBytes(getBulkRequestPath(indexName), bodyBytes, additionalHeaders, context);
    }

    /**
     * Partitions the bulk response into success / non-retryable / retryable buckets,
     * emits one failed document stream record per non-retryable failure, and compacts {@code pendingDocs}
     * in place so it contains only the items that should be retried.
     *
     * <p>Behavior change vs. the previous BitSet-based compactor: items whose error
     * type is in {@link org.opensearch.migrations.BulkDocErrorTypes#NON_RETRYABLE}
     * are written to the failed document stream immediately and removed from {@code pendingDocs} so the
     * retry loop does not keep hammering them.
     *
     * @return number of documents removed because they succeeded (or were allowlisted)
     */
    private int compactPendingDocs(
        String indexName,
        List<PreparedOperation> pendingDocs,
        BulkResponse resp,
        IRfsContexts.IRequestContext context,
        DocumentExceptionAllowlist allowlist
    ) {
        ItemPartition partition = BulkResponseParser.partitionItems(resp.body, allowlist);
        if (partition == null) {
            // Can't parse response - assume all failed, retry all
            return 0;
        }
        if (partition.itemCount() != pendingDocs.size()) {
            // Items can only be matched to operations by position, so a response that doesn't have exactly one item
            // per operation can't be trusted for any of them. Retry all rather than treat missing items as successes.
            log.atWarn()
                .setMessage("Bulk response on index '{}' has {} items for {} operations; retrying all of them")
                .addArgument(indexName)
                .addArgument(partition::itemCount)
                .addArgument(pendingDocs::size)
                .log();
            return 0;
        }

        // Snapshot the ops we're about to drop so we can emit failed document stream records before mutating.
        for (ItemFailure failure : partition.getNonRetryableFailures()) {
            BulkOperationSpec op = failure.getPosition() < pendingDocs.size()
                ? pendingDocs.get(failure.getPosition()).operation().get()
                : null;
            emitFailedDocumentStreamRecord(indexName, op, failure, FailureClass.NON_RETRYABLE);
        }
        countFailedDocuments(context, FailureClass.NON_RETRYABLE, partition.getNonRetryableFailures().size());

        // Keep only retryable failures in pendingDocs, in original order.
        BitSet keep = new BitSet(pendingDocs.size());
        for (ItemFailure failure : partition.getRetryableFailures()) {
            keep.set(failure.getPosition());
        }
        int writeIdx = 0;
        for (int i = keep.nextSetBit(0); i >= 0; i = keep.nextSetBit(i + 1)) {
            pendingDocs.set(writeIdx++, pendingDocs.get(i));
        }
        int removed = pendingDocs.size() - writeIdx;
        pendingDocs.subList(writeIdx, pendingDocs.size()).clear();
        // removed = successes + non-retryable; return only the success count for the existing log line.
        return removed - partition.getNonRetryableFailures().size();
    }

    /**
     * Emit one failed document stream record per item still in {@code pendingOps} after all retries were
     * exhausted. Best-effort correlation with per-item response details from the last
     * failing response (carried on {@link OperationFailed}); falls back to null
     * responseItem when the error is not an {@code OperationFailed} (e.g. a network
     * failure that never produced a bulk response).
     *
     * <p>The configured {@code allowlist} is honored when classifying the last response:
     * allowlisted items are routed to {@code successPositions} and are therefore absent from the
     * retryable-failure list used for correlation, so an allowlisted item's {@code failureType} or
     * documentId can never be attached to a real retryable failure's failed document stream record.
     *
     * <p><b>Correlation is by retryable-failure index, not by raw response position.</b> By the time
     * this runs, {@code compactPendingDocs} has already compacted {@code pendingOps} down to exactly
     * the last response's retryable failures, kept in ascending-position order.
     * {@code partitionItems(...).getRetryableFailures()} recomputes that same list in that same order,
     * so {@code pendingOps.get(k)} and {@code retryableFailures.get(k)} are the same k-th retryable
     * item. This alignment is stable across compaction and — unlike a document-id lookup — works for
     * server-generated ids (the id was stripped from the request), for which it also recovers the
     * cluster-assigned id carried on the response item.
     *
     * <p>If the last response can't be parsed (no {@code OperationFailed}, e.g. a network failure, or
     * an unparseable body) there is no failure list to align against, so every pending op is emitted
     * with a null failure (null failureType/responseItem); the document body is still preserved.
     */
    private void emitRetryExhaustedToFailedDocumentStream(
        String indexName,
        List<PreparedOperation> pendingOps,
        Throwable error,
        DocumentExceptionAllowlist allowlist
    ) {
        // Retry exhausted by Reactor wraps the underlying OperationFailed inside a
        // RetryExhausted/RuntimeException — unwrap the cause chain to recover the
        // per-item response details from the last attempt.
        HttpResponse lastResponse = findOperationFailed(error).map(f -> f.response).orElse(null);
        List<ItemFailure> retryableFailures = List.of();
        boolean itemLevel = lastResponse != null && lastResponse.body != null
            && !new BulkResponse(lastResponse.statusCode, null, null, null).hasBadStatusCode();
        if (itemLevel) {
            ItemPartition partition = BulkResponseParser.partitionItems(lastResponse.body, allowlist);
            if (partition != null) {
                retryableFailures = partition.getRetryableFailures();
            }
        }
        // pendingOps and retryableFailures are the same retryable items in the same order (see javadoc);
        // when they can't be aligned (unparseable response, or a size mismatch that should never happen)
        // the failure is left null rather than risking a cross-item mis-attribution.
        boolean aligned = retryableFailures.size() == pendingOps.size();
        if (!aligned && !retryableFailures.isEmpty()) {
            log.atWarn()
                .setMessage("Retry-exhausted failed-document-stream correlation skipped for index '{}': "
                    + "{} pending ops vs {} retryable failures in the last response")
                .addArgument(indexName)
                .addArgument(pendingOps.size())
                .addArgument(retryableFailures.size())
                .log();
        }
        // Without per-item results (no response, or a whole-request error status), every operation shares the
        // request-level failure.
        ItemFailure requestFailure = itemLevel ? null : wholeRequestFailure(lastResponse, error);
        for (int i = 0; i < pendingOps.size(); i++) {
            ItemFailure failure = aligned ? retryableFailures.get(i) : requestFailure;
            emitFailedDocumentStreamRecord(indexName, pendingOps.get(i).operation().get(), failure, FailureClass.RETRYABLE_EXHAUSTED);
        }
    }

    private static Optional<OperationFailed> findOperationFailed(Throwable error) {
        for (var cursor = error; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof OperationFailed opFailed) {
                return Optional.of(opFailed);
            }
        }
        return Optional.empty();
    }

    /**
     * A failure that applies to every operation in a request: an error status ({@code failureType} {@code http_<status>},
     * with the truncated body) or no response at all ({@code failureType} {@code no_response}, with the exception).
     */
    static ItemFailure wholeRequestFailure(HttpResponse response, Throwable error) {
        var detail = OBJECT_MAPPER.createObjectNode();
        String failureType;
        if (response != null) {
            failureType = "http_" + response.statusCode;
            detail.put("status", response.statusCode);
            detail.put("body", truncateMessageIfNeeded(response.body, BULK_TRUNCATED_RESPONSE_MAX_LENGTH));
        } else {
            failureType = "no_response";
            Throwable transportError = error;
            for (var cursor = error; cursor != null; cursor = cursor.getCause()) {
                transportError = cursor;
                if (cursor instanceof NoResponseException && cursor.getCause() != null) {
                    transportError = cursor.getCause();
                    break;
                }
            }
            detail.put("exception", transportError != null ? transportError.getClass().getName() : null);
            detail.put("message", transportError != null ? transportError.getMessage() : null);
        }
        return new ItemFailure(0, null, failureType, detail.toString());
    }

    void emitFailedDocumentStreamRecord(String indexName, BulkOperationSpec op, ItemFailure failure, FailureClass cls) {
        if (failedDocumentStreamSink == null) {
            return;
        }
        try {
            String docId = failure != null && failure.getDocumentId() != null
                ? failure.getDocumentId()
                : extractDocumentId(op);
            JsonNode requestItem = op != null ? OBJECT_MAPPER.valueToTree(op) : null;
            // Persist the original source-index document rather than the transformed one.
            // The transformed body is what was actually sent, but the failed document stream is meant to let
            // operators inspect/replay the source document, so swap it in when available.
            if (requestItem instanceof ObjectNode && op != null && op.getOriginalSource() != null) {
                ((ObjectNode) requestItem).set("document", OBJECT_MAPPER.valueToTree(op.getOriginalSource()));
            }
            JsonNode responseItem = failure != null && failure.getResponseItemJson() != null
                ? OBJECT_MAPPER.readTree(failure.getResponseItemJson()) : null;
            String failureType = failure != null ? failure.getErrorType() : null;
            FailedDocumentStreamRecord failedDocumentStreamRecord = FailedDocumentStreamRecord.builder()
                .sessionId(failedDocumentStreamSessionId)
                .workerId(failedDocumentStreamWorkerId)
                .workItemId(failedDocumentStreamWorkItemId)
                .targetIndex(indexName)
                .documentId(docId)
                .failureType(failureType)
                .failureClass(cls)
                .timestamp(Instant.now().toString())
                .requestItem(requestItem)
                .responseItem(responseItem)
                .build();
            failedDocumentStreamSink.write(failedDocumentStreamRecord).subscribe(
                v -> {},
                err -> log.atError().setCause(err)
                    .setMessage("Failed to buffer failed document stream record for index {}: {}")
                    .addArgument(indexName)
                    .addArgument(err.getMessage())
                    .log()
            );
        } catch (Exception e) {
            log.atError().setCause(e)
                .setMessage("Unexpected error while emitting failed document stream record for index {}").addArgument(indexName).log();
        }
    }

    static String extractDocumentId(BulkOperationSpec op) {
        if (op == null) return null;
        try {
            // The bulk operation Jackson tree wraps the request payload under an
            // "operation" object that has an "_id" field; the bulk-API action key
            // ("index"/"create"/"update") is also a fallback when callers pass
            // pre-shaped nodes through Jackson.
            JsonNode tree = OBJECT_MAPPER.valueToTree(op);
            if (tree == null) return null;
            JsonNode meta = tree.path("operation").path("_id");
            if (meta.isMissingNode() || meta.isNull()) {
                meta = tree.path("index").path("_id");
            }
            if (meta.isMissingNode() || meta.isNull()) {
                meta = tree.path("create").path("_id");
            }
            if (meta.isMissingNode() || meta.isNull()) {
                meta = tree.path("update").path("_id");
            }
            return meta.isMissingNode() || meta.isNull() ? null : meta.asText();
        } catch (Exception e) {
            return null;
        }
    }

    public HttpResponse refresh(IRfsContexts.IRequestContext context) {
        String targetPath = "_refresh";
        return client.get(targetPath, context);
    }

    public static class BulkResponse extends HttpResponse {
        private static final Pattern FAILED_OPERATIONS = Pattern.compile("\"errors\"\\s*:\\s*true");
        private static final Pattern NO_FAILED_OPERATIONS = Pattern.compile("\"errors\"\\s*:\\s*false");
        public final MigrationMetrics.Operations target;
        public final MigrationMetrics.Requests requests;

        public BulkResponse(int statusCode, String statusText, Map<String, String> headers, String body) {
            this(statusCode, statusText, headers, body,
                MigrationMetrics.Operations.ZERO, MigrationMetrics.Requests.ZERO);
        }

        public BulkResponse(int statusCode, String statusText, Map<String, String> headers, String body,
                            MigrationMetrics.Operations target, MigrationMetrics.Requests requests) {
            super(statusCode, statusText, headers, body);
            this.target = target;
            this.requests = requests;
        }

        public boolean hasBadStatusCode() {
            return !(statusCode == HttpURLConnection.HTTP_OK || statusCode == HttpURLConnection.HTTP_CREATED);
        }

        public boolean hasFailedOperations() {
            // The OpenSearch Bulk API response body is JSON and contains a top-level "errors" field that indicates
            // whether any of the individual operations in the bulk request failed. Rather than marshalling the entire
            // response as JSON, just check for the string value.

            return body != null && FAILED_OPERATIONS.matcher(body).find();
        }

        /**
         * True only when the body positively reports {@code "errors": false}. An empty or truncated body reports
         * nothing, so it can't be counted as success for any operation.
         */
        public boolean reportsNoFailedOperations() {
            return body != null && !hasFailedOperations() && NO_FAILED_OPERATIONS.matcher(body).find();
        }

        public List<String> getSuccessfulDocs() {
            return getSuccessfulDocs(DocumentExceptionAllowlist.empty());
        }

        public List<String> getSuccessfulDocs(DocumentExceptionAllowlist allowlist) {
            try {
                return BulkResponseParser.findSuccessDocs(body, allowlist);
            } catch (IOException ioe) {
                log.warn("Unable to process bulk request for success", ioe);
                return List.of();
            }
        }

        public String getFailureMessage() {
            String failureMessage;
            if (hasBadStatusCode()) {
                failureMessage = "Bulk request failed.  Status code: " + statusCode + ", Response body: " + body;
            } else {
                failureMessage = "Bulk request succeeded, but some operations failed.  Response body: " + body;
            }

            return failureMessage;
        }
    }

    public static class OperationFailed extends RuntimeException {
        public final transient HttpResponse response;

        public OperationFailed(String message, HttpResponse response) {
            super(message +"\nBody:\n" + response);

            this.response = response;
        }
    }

    public static class UnexpectedStatusCode extends OperationFailed {
        public UnexpectedStatusCode(HttpResponse response) {
            super("Unexpected status code " + response.statusCode, response);
        }
    }
}
