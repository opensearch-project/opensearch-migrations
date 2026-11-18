package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.opensearch.migrations.AwarenessAttributeSettings;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.common.retry.AdaptiveLimit;
import org.opensearch.migrations.bulkload.common.retry.BulkFlowMetrics;
import org.opensearch.migrations.bulkload.common.retry.BulkFlowStats;
import org.opensearch.migrations.bulkload.common.retry.BulkRetryPolicy;
import org.opensearch.migrations.bulkload.common.retry.RetryBudget;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.parsing.BulkResponseParser;
import org.opensearch.migrations.parsing.BulkResponseParser.ItemFailure;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamSink;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.handler.timeout.ReadTimeoutException;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Public facade for target administration and bulk document writes.
 *
 * <p>The administrative, bulk execution, and failed-document implementations live in focused collaborators. This
 * class retains the existing API, version-specific routing hooks, and collection-bound client behavior.
 */
@Slf4j
public abstract class OpenSearchClient {
    protected static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createDefaultMapper();

    public static final String INVALID_SOURCE_FORMAT = "invalid_source_format";
    public static final Retry CHECK_IF_ITEM_EXISTS_RETRY_STRATEGY =
        OpenSearchAdminClient.CHECK_IF_ITEM_EXISTS_RETRY_STRATEGY;
    public static final int MAX_UNKNOWN_FAILURE_RETRIES = 3;
    public static final int MAX_NO_RESPONSE_RETRIES = 5;
    public static final int MAX_ITEM_FAILURE_RETRIES = 3;
    static final int FAILED_ATTEMPTS_BEFORE_SPLIT = 2;
    public static final int BULK_TRUNCATED_RESPONSE_MAX_LENGTH = 1500;
    public static final String BULK_REQUEST_TRACE_LOGGER = "BulkRequestTrace";
    public static final int DEFAULT_BULK_TRACE_MAX_BYTES = 1024 * 1024;
    public static final String SNAPSHOT_PREFIX_STR = OpenSearchAdminClient.SNAPSHOT_PREFIX_STR;
    static final Duration OVERSIZED_OPERATION_WARNING_INTERVAL = Duration.ofMinutes(1);
    public static final String DOCUMENT_TOO_LARGE = "document_too_large";
    public static final String MEASURED_REQUEST_BODY = "requestBody";
    static final String RAW_PATH = "raw";
    static final String TRANSFORMED_PATH = "transformed";
    static final java.util.Set<String> REPORTED_ITEM_ERROR_TYPES = BulkRequestExecutor.REPORTED_ITEM_ERROR_TYPES;
    static final String OTHER_ITEM_ERROR = BulkRequestExecutor.OTHER_ITEM_ERROR;

    protected final RestClient client;
    protected final FailedRequestsLogger failedRequestsLogger;
    private final Version version;
    private final CompressionMode compressionMode;
    private final int maxDocsPerBulkRequest;
    private final long maxBytesPerBulkRequest;
    /** Maximum request or response body bytes included in each bulk trace entry; 0 logs the complete body. */
    private volatile int bulkTraceMaxBytes = DEFAULT_BULK_TRACE_MAX_BYTES;
    private final FailedDocumentReporter failedDocumentReporter;
    private final BulkRuntime bulkRuntime;
    private final OpenSearchAdminClient adminClient;
    private final BulkRequestExecutor bulkRequestExecutor;
    private final BulkRequestPreparer bulkRequestPreparer;

    protected OpenSearchClient(
        RestClient client,
        FailedRequestsLogger failedRequestsLogger,
        Version version,
        CompressionMode compressionMode,
        int maxDocsPerBulkRequest,
        long maxBytesPerBulkRequest
    ) {
        this(
            client,
            failedRequestsLogger,
            version,
            compressionMode,
            maxDocsPerBulkRequest,
            maxBytesPerBulkRequest,
            new FailedDocumentReporter(),
            new BulkRuntime()
        );
    }

    private OpenSearchClient(
        RestClient client,
        FailedRequestsLogger failedRequestsLogger,
        Version version,
        CompressionMode compressionMode,
        int maxDocsPerBulkRequest,
        long maxBytesPerBulkRequest,
        FailedDocumentReporter failedDocumentReporter,
        BulkRuntime bulkRuntime
    ) {
        if (maxDocsPerBulkRequest < 1 || maxBytesPerBulkRequest < 1) {
            throw new IllegalArgumentException("Bulk request count and byte limits must be positive");
        }
        this.client = client;
        this.failedRequestsLogger = failedRequestsLogger;
        this.version = version;
        this.compressionMode = compressionMode;
        this.maxDocsPerBulkRequest = maxDocsPerBulkRequest;
        this.maxBytesPerBulkRequest = maxBytesPerBulkRequest;
        this.failedDocumentReporter = failedDocumentReporter;
        this.bulkRuntime = bulkRuntime;
        this.adminClient = new OpenSearchAdminClient(client, version, this::getCreateIndexPath);
        this.bulkRequestExecutor = new BulkRequestExecutor(
            client,
            failedRequestsLogger,
            compressionMode,
            failedDocumentReporter,
            bulkRuntime,
            this::getBulkRequestPath,
            this::getBulkTraceMaxBytes
        );
        this.bulkRequestPreparer = new BulkRequestPreparer(
            maxDocsPerBulkRequest,
            maxBytesPerBulkRequest,
            failedDocumentReporter,
            bulkRuntime,
            bulkRequestExecutor
        );
    }

    public OpenSearchClient forCollection(String serverlessCollection) {
        return new CollectionBoundClient(this, serverlessCollection);
    }

    public boolean isCollectionRouted() {
        return client.getConnectionContext().isCollectionRouted();
    }

    public Version getClusterVersion() {
        return version;
    }

    public void setFailedDocumentStreamContext(
        FailedDocumentStreamSink failedDocumentStreamSink,
        String sessionId,
        String workerId
    ) {
        failedDocumentReporter.setContext(failedDocumentStreamSink, sessionId, workerId);
    }

    public void setFailedDocumentStreamWorkItem(String workItemId) {
        failedDocumentReporter.setWorkItem(workItemId);
    }

    public FailedDocumentStreamSink getFailedDocumentStreamSink() {
        return failedDocumentReporter.getSink();
    }

    public void setMaxDocumentSizeBytes(long maxDocumentSizeBytes) {
        if (maxDocumentSizeBytes < 0) {
            throw new IllegalArgumentException("maxDocumentSizeBytes must be >= 0, got " + maxDocumentSizeBytes);
        }
        bulkRuntime.maxDocumentSizeBytes = maxDocumentSizeBytes;
    }

    public long getMaxDocumentSizeBytes() {
        return bulkRuntime.maxDocumentSizeBytes;
    }

    public void setBulkTraceMaxBytes(int bulkTraceMaxBytes) {
        if (bulkTraceMaxBytes < 0) {
            throw new IllegalArgumentException("bulkTraceMaxBytes must be >= 0, got " + bulkTraceMaxBytes);
        }
        this.bulkTraceMaxBytes = bulkTraceMaxBytes;
    }

    private int getBulkTraceMaxBytes() {
        return bulkTraceMaxBytes;
    }

    public void rejectOversizedDocument(
        String indexName,
        BulkOperationSpec op,
        long size,
        String measured,
        IRfsContexts.IRequestContext context
    ) {
        failedDocumentReporter.rejectOversizedDocument(
            indexName,
            op,
            size,
            bulkRuntime.maxDocumentSizeBytes,
            measured,
            context
        );
    }

    public AwarenessAttributeSettings getAwarenessAttributeSettings() {
        return adminClient.getAwarenessAttributeSettings();
    }

    public Optional<ObjectNode> createLegacyTemplate(
        String templateName,
        ObjectNode settings,
        IRfsContexts.ICheckedIdempotentPutRequestContext context
    ) {
        return adminClient.createLegacyTemplate(templateName, settings, context);
    }

    public Optional<ObjectNode> createComponentTemplate(
        String templateName,
        ObjectNode settings,
        IRfsContexts.ICheckedIdempotentPutRequestContext context
    ) {
        return adminClient.createComponentTemplate(templateName, settings, context);
    }

    public Optional<ObjectNode> createIndexTemplate(
        String templateName,
        ObjectNode settings,
        IRfsContexts.ICheckedIdempotentPutRequestContext context
    ) {
        return adminClient.createIndexTemplate(templateName, settings, context);
    }

    public boolean hasLegacyTemplate(String templateName) {
        return adminClient.hasLegacyTemplate(templateName);
    }

    public boolean hasComponentTemplate(String templateName) {
        return adminClient.hasComponentTemplate(templateName);
    }

    public boolean hasIndexTemplate(String templateName) {
        return adminClient.hasIndexTemplate(templateName);
    }

    public boolean hasIndex(String indexName) {
        return adminClient.hasIndex(indexName);
    }

    protected abstract String getCreateIndexPath(String indexName);

    public Optional<ObjectNode> createIndex(
        String indexName,
        ObjectNode settings,
        IRfsContexts.ICheckedIdempotentPutRequestContext context
    ) {
        return adminClient.createIndex(indexName, settings, context);
    }

    public void registerSnapshotRepo(
        String repoName,
        ObjectNode settings,
        IRfsContexts.ICreateSnapshotContext context
    ) {
        adminClient.registerSnapshotRepo(repoName, settings, context, true);
    }

    public void registerSnapshotRepo(
        String repoName,
        ObjectNode settings,
        IRfsContexts.ICreateSnapshotContext context,
        boolean verify
    ) {
        adminClient.registerSnapshotRepo(repoName, settings, context, verify);
    }

    public void createSnapshot(
        String repoName,
        String snapshotName,
        ObjectNode settings,
        IRfsContexts.ICreateSnapshotContext context
    ) {
        adminClient.createSnapshot(repoName, snapshotName, settings, context);
    }

    public Optional<ObjectNode> getSnapshotStatus(
        String repoName,
        String snapshotName,
        IRfsContexts.ICreateSnapshotContext context
    ) {
        return adminClient.getSnapshotStatus(repoName, snapshotName, context);
    }

    protected abstract String getBulkRequestPath(String indexName);

    protected Retry getBulkRetryStrategy(BulkRetryPolicy.Batch batch) {
        return bulkRuntime.bulkRetryPolicy.retryFor(batch, BulkRetryPolicy.DEFAULT_MAX_BACKOFF);
    }

    public void setBulkRetryBudget(RetryBudget budget) {
        bulkRequestExecutor.setBulkRetryBudget(budget);
    }

    public void setBulkFlowMetrics(BulkFlowMetrics metrics) {
        bulkRequestExecutor.setBulkFlowMetrics(metrics);
    }

    public BulkFlowStats.Summary getBulkFlowSummary() {
        return bulkRequestExecutor.getBulkFlowSummary();
    }

    public RetryBudget getBulkRetryBudget() {
        return bulkRequestExecutor.getBulkRetryBudget();
    }

    public void setBulkResponseTimeout(Duration bulkResponseTimeout) {
        bulkRequestExecutor.setBulkResponseTimeout(bulkResponseTimeout);
    }

    public Mono<BulkResponse> sendBulkRequest(
        String indexName,
        List<? extends BulkOperationSpec> docs,
        IRfsContexts.IRequestContext context
    ) {
        return bulkRequestPreparer.sendBulkRequest(indexName, docs, context, this::getBulkRetryStrategy);
    }

    public Mono<BulkResponse> sendBulkRequest(
        String indexName,
        List<? extends BulkOperationSpec> docs,
        IRfsContexts.IRequestContext context,
        boolean allowServerGeneratedIds,
        DocumentExceptionAllowlist allowlist
    ) {
        return bulkRequestPreparer.sendBulkRequest(
            indexName,
            docs,
            context,
            allowServerGeneratedIds,
            allowlist,
            this::getBulkRetryStrategy
        );
    }

    public Mono<BulkResponse> sendBulkRequest(
        String indexName,
        List<? extends BulkOperationSpec> docs,
        IRfsContexts.IRequestContext context,
        boolean allowServerGeneratedIds,
        DocumentExceptionAllowlist allowlist,
        int splitRequestConcurrency
    ) {
        return bulkRequestPreparer.sendBulkRequest(
            indexName,
            docs,
            context,
            allowServerGeneratedIds,
            allowlist,
            splitRequestConcurrency,
            this::getBulkRetryStrategy
        );
    }

    public Mono<BulkResponse> sendBulkRequestRaw(
        String indexName,
        List<Document> docs,
        IRfsContexts.IRequestContext context,
        boolean allowServerGeneratedIds,
        DocumentExceptionAllowlist allowlist
    ) {
        return bulkRequestPreparer.sendBulkRequestRaw(
            indexName,
            docs,
            context,
            allowServerGeneratedIds,
            allowlist,
            this::getBulkRetryStrategy
        );
    }

    public Mono<BulkResponse> sendBulkRequestRaw(
        String indexName,
        List<Document> docs,
        IRfsContexts.IRequestContext context,
        boolean allowServerGeneratedIds,
        DocumentExceptionAllowlist allowlist,
        int splitRequestConcurrency
    ) {
        return bulkRequestPreparer.sendBulkRequestRaw(
            indexName,
            docs,
            context,
            allowServerGeneratedIds,
            allowlist,
            splitRequestConcurrency,
            this::getBulkRetryStrategy
        );
    }

    public void reportInvalidDocumentSource(String indexName, String docId, byte[] source, Exception cause) {
        reportInvalidDocumentSource(indexName, docId, source, cause, null);
    }

    public void reportInvalidDocumentSource(
        String indexName,
        String docId,
        byte[] source,
        Exception cause,
        IRfsContexts.IRequestContext context
    ) {
        failedDocumentReporter.reportInvalidDocumentSource(indexName, docId, source, cause, context);
    }

    public record PreparedRawDocument(Document document, BulkNdjson.EncodedOperation prepared) {
        public PreparedRawDocument {
            Objects.requireNonNull(document);
            Objects.requireNonNull(prepared);
        }
    }

    public Mono<BulkResponse> sendBulkRequestPreparedRaw(
        String indexName,
        List<PreparedRawDocument> docs,
        IRfsContexts.IRequestContext context,
        DocumentExceptionAllowlist allowlist,
        int splitRequestConcurrency
    ) {
        return bulkRequestPreparer.sendBulkRequestPreparedRaw(
            indexName,
            docs,
            context,
            allowlist,
            splitRequestConcurrency,
            this::getBulkRetryStrategy
        );
    }

    public void setBulkInFlightLimit(AdaptiveLimit limit) {
        bulkRequestExecutor.setBulkInFlightLimit(limit);
    }

    public AdaptiveLimit getBulkInFlightLimit() {
        return bulkRequestExecutor.getBulkInFlightLimit();
    }

    public HttpResponse refresh(IRfsContexts.IRequestContext context) {
        return adminClient.refresh(context);
    }

    void emitFailedDocumentStreamRecord(
        String indexName,
        BulkOperationSpec op,
        ItemFailure failure,
        FailureClass failureClass
    ) {
        failedDocumentReporter.emit(indexName, op, failure, failureClass);
    }

    static String traceBody(byte[] body, int limit) {
        return BulkAttemptSender.traceBody(body, limit);
    }

    static String traceBody(ByteBuffer[] body, int limit) {
        return BulkAttemptSender.traceBody(body, limit);
    }

    static AdaptiveLimit.Signal limitSignal(HttpResponse response) {
        return BulkAttemptSender.limitSignal(response);
    }

    static AdaptiveLimit.Signal limitSignal(Throwable error) {
        return BulkAttemptSender.limitSignal(error);
    }

    static BulkFlowMetrics.AttemptOutcome attemptOutcome(HttpResponse response) {
        return BulkAttemptSender.attemptOutcome(response);
    }

    static BulkFlowMetrics.AttemptOutcome attemptOutcome(Throwable error) {
        return BulkAttemptSender.attemptOutcome(error);
    }

    static ItemFailure wholeRequestFailure(HttpResponse response, Throwable error) {
        return BulkRequestExecutor.wholeRequestFailure(response, error);
    }

    public static MigrationMetrics.Failures failuresOf(FailureClass failureClass, long count) {
        return FailedDocumentReporter.failuresOf(failureClass, count);
    }

    static String effectiveIndexOf(BulkOperationSpec op, String requestIndex) {
        return FailedDocumentReporter.effectiveIndexOf(op, requestIndex);
    }

    static String extractDocumentId(BulkOperationSpec op) {
        return FailedDocumentReporter.extractDocumentId(op);
    }

    public static class BulkResponse extends HttpResponse {
        private static final Pattern FAILED_OPERATIONS = Pattern.compile("\"errors\"\\s*:\\s*true");
        private static final Pattern NO_FAILED_OPERATIONS = Pattern.compile("\"errors\"\\s*:\\s*false");
        public final MigrationMetrics.Operations target;
        public final MigrationMetrics.Requests requests;
        public final MigrationMetrics.Failures failures;

        public BulkResponse(int statusCode, String statusText, Map<String, String> headers, String body) {
            this(
                statusCode,
                statusText,
                headers,
                body,
                MigrationMetrics.Operations.ZERO,
                MigrationMetrics.Requests.ZERO
            );
        }

        public BulkResponse(
            int statusCode,
            String statusText,
            Map<String, String> headers,
            String body,
            MigrationMetrics.Operations target,
            MigrationMetrics.Requests requests
        ) {
            this(statusCode, statusText, headers, body, target, requests, MigrationMetrics.Failures.ZERO);
        }

        public BulkResponse(
            int statusCode,
            String statusText,
            Map<String, String> headers,
            String body,
            MigrationMetrics.Operations target,
            MigrationMetrics.Requests requests,
            MigrationMetrics.Failures failures
        ) {
            super(statusCode, statusText, headers, body);
            this.target = target;
            this.requests = requests;
            this.failures = failures;
        }

        BulkResponse plusFailures(MigrationMetrics.Failures more) {
            return more.total() == 0
                ? this
                : new BulkResponse(statusCode, statusText, headers, body, target, requests, failures.plus(more));
        }

        public boolean hasBadStatusCode() {
            return !(statusCode == HttpURLConnection.HTTP_OK || statusCode == HttpURLConnection.HTTP_CREATED);
        }

        public boolean hasFailedOperations() {
            return body != null && FAILED_OPERATIONS.matcher(body).find();
        }

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
            if (hasBadStatusCode()) {
                return "Bulk request failed.  Status code: " + statusCode + ", Response body: " + body;
            }
            return "Bulk request succeeded, but some operations failed.  Response body: " + body;
        }
    }

    public static class OperationFailed extends RuntimeException {
        public final transient HttpResponse response;

        public OperationFailed(String message, HttpResponse response) {
            super(message + "\nBody:\n" + response);
            this.response = response;
        }
    }

    public static class UnexpectedStatusCode extends OperationFailed implements HttpRetryClassifier.StatusCodeFailure {
        public UnexpectedStatusCode(HttpResponse response) {
            super("Unexpected status code " + response.statusCode, response);
        }

        @Override
        public int statusCode() {
            return response.statusCode;
        }
    }

    public static class TargetAuthenticationFailed extends RuntimeException {
        public TargetAuthenticationFailed(int statusCode, OperationFailed failure) {
            super("The target refused the credentials (status " + statusCode + ")", failure);
        }
    }

    public static class NoResponseException extends RuntimeException {
        public final Duration elapsed;

        public NoResponseException(Throwable cause) {
            this(cause, Duration.ZERO);
        }

        public NoResponseException(Throwable cause, Duration elapsed) {
            super("No response to bulk request after " + elapsed.toMillis() + " ms: " + cause, cause);
            this.elapsed = elapsed;
        }

        public boolean isResponseTimeout() {
            for (var cursor = getCause(); cursor != null; cursor = cursor.getCause()) {
                if (cursor instanceof ReadTimeoutException || cursor instanceof TimeoutException) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class CollectionBoundClient extends OpenSearchClient {
        private final OpenSearchClient unbound;

        private CollectionBoundClient(OpenSearchClient unbound, String serverlessCollection) {
            super(
                unbound.client.withDefaultHeaders(ServerlessCollectionRouting.headersFor(serverlessCollection)),
                unbound.failedRequestsLogger,
                unbound.version,
                unbound.compressionMode,
                unbound.maxDocsPerBulkRequest,
                unbound.maxBytesPerBulkRequest,
                unbound.failedDocumentReporter,
                unbound.bulkRuntime
            );
            this.unbound = unbound;
        }

        @Override
        protected String getCreateIndexPath(String indexName) {
            return unbound.getCreateIndexPath(indexName);
        }

        @Override
        protected String getBulkRequestPath(String indexName) {
            return unbound.getBulkRequestPath(indexName);
        }

        @Override
        protected Retry getBulkRetryStrategy(BulkRetryPolicy.Batch batch) {
            return unbound.getBulkRetryStrategy(batch);
        }
    }
}
