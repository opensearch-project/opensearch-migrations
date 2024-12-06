package org.opensearch.migrations.bulkload.workcoordination;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Spliterators;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.opensearch.migrations.bulkload.tracing.IWorkCoordinationContexts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Lombok;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OpenSearchWorkCoordinator implements IWorkCoordinator {
    public static final String INDEX_NAME = ".migrations_working_state";
    public static final int MAX_REFRESH_RETRIES = 6;
    public static final int MAX_SETUP_RETRIES = 6;
    static final long ACQUIRE_WORK_RETRY_BASE_MS = 10;
    // we'll retry lease acquisitions for up to
    static final int MAX_DRIFT_RETRIES = 13; // last delay before failure: 40 seconds
    static final int MAX_MALFORMED_ASSIGNED_WORK_DOC_RETRIES = 17; // last delay before failure: 655.36 seconds
    static final int MAX_ASSIGNED_DOCUMENT_NOT_FOUND_RETRY_INTERVAL = 60 * 1000;
    static final int MAX_CREATE_SUCCESSOR_WORK_ITEMS_RETRIES = 10;
    static final int CREATE_SUCCESSOR_WORK_ITEMS_RETRY_BASE_MS = 10; // last delay before failure: 10 seconds
    static final int MAX_CREATE_UNASSIGNED_SUCCESSOR_WORK_ITEM_RETRIES = 7; // last delay before failure: 1.2 seconds
    static final int MAX_MARK_AS_COMPLETED_RETRIES = 7; // last delay before failure: 1.2 seconds


    public static final String SCRIPT_VERSION_TEMPLATE = "{SCRIPT_VERSION}";
    public static final String WORKER_ID_TEMPLATE = "{WORKER_ID}";
    public static final String CLIENT_TIMESTAMP_TEMPLATE = "{CLIENT_TIMESTAMP}";
    public static final String EXPIRATION_WINDOW_TEMPLATE = "{EXPIRATION_WINDOW}";
    public static final String CLOCK_DEVIATION_SECONDS_THRESHOLD_TEMPLATE = "{CLOCK_DEVIATION_SECONDS_THRESHOLD}";
    public static final String OLD_EXPIRATION_THRESHOLD_TEMPLATE = "{OLD_EXPIRATION_THRESHOLD}";
    public static final String SUCCESSOR_WORK_ITEM_IDS_TEMPLATE = "{SUCCESSOR_WORK_ITEM_IDS}";

    public static final String RESULT_OPENSSEARCH_FIELD_NAME = "result";
    public static final String EXPIRATION_FIELD_NAME = "expiration";
    public static final String UPDATED_COUNT_FIELD_NAME = "updated";
    public static final String LEASE_HOLDER_ID_FIELD_NAME = "leaseHolderId";
    public static final String VERSION_CONFLICTS_FIELD_NAME = "version_conflicts";
    public static final String COMPLETED_AT_FIELD_NAME = "completedAt";
    public static final String SOURCE_FIELD_NAME = "_source";
    public static final String SUCCESSOR_ITEMS_FIELD_NAME = "successor_items";
    public static final String SUCCESSOR_ITEM_DELIMITER = ",";

    public static final int CREATED_RESPONSE_CODE = 201;
    public static final int CONFLICT_RESPONSE_CODE = 409;

    public static final String QUERY_INCOMPLETE_EXPIRED_ITEMS_STR = "    \"query\": {\n"
        + "      \"bool\": {"
        + "        \"must\": ["
        + "          {"
        + "            \"range\": {"
        + "              \"" + EXPIRATION_FIELD_NAME + "\": { \"lt\": " + OLD_EXPIRATION_THRESHOLD_TEMPLATE + " }"
        + "            }"
        + "          }"
        + "        ],"
        + "        \"must_not\": ["
        + "          { \"exists\":"
        + "            { \"field\": \"" + COMPLETED_AT_FIELD_NAME + "\"}"
        + "          }"
        + "        ]"
        + "      }"
        + "    }";

    /**
     * Helper class to make sure that we throw retryable exceptions after the initial lease
     * would have expired.  This is here to mitigate the risk of acquiring a lease on a work
     * item but getting a transient exception while looking it up.
     */
    @AllArgsConstructor
    private static class LeaseChecker {
        Duration leaseDuration;
        final long startTimeNanos;

        void checkRetryWaitTimeOrThrow(Exception e, int retryCountSoFar, Duration retryInDuration) {
            if (waitExtendsPastLease(retryInDuration)) {
                throw new RetriesExceededException(e, retryCountSoFar);
            }
        }

        private boolean waitExtendsPastLease(Duration nextRetryAtDuration) {
            var elapsedTimeNanos = System.nanoTime() - startTimeNanos;
            return leaseDuration.minus(nextRetryAtDuration.plusNanos(elapsedTimeNanos)).isNegative();
        }
    }

    /**
     * This is a WorkAcquisitionOutcome for a WorkItem that may or may not already have successor work items.
     */
    @Getter
    @AllArgsConstructor
    @ToString
    static class WorkItemWithPotentialSuccessors {
        final String workItemId;
        final Instant leaseExpirationTime;
        final ArrayList<String> successorWorkItemIds;
    }

    private final long tolerableClientServerClockDifferenceSeconds;
    private final AbstractedHttpClient httpClient;
    private final String workerId;
    private final ObjectMapper objectMapper;
    @Getter
    private final Clock clock;

    public OpenSearchWorkCoordinator(
        AbstractedHttpClient httpClient,
        long tolerableClientServerClockDifferenceSeconds,
        String workerId
    ) {
        this(httpClient, tolerableClientServerClockDifferenceSeconds, workerId, Clock.systemUTC());
    }

    public OpenSearchWorkCoordinator(
        AbstractedHttpClient httpClient,
        long tolerableClientServerClockDifferenceSeconds,
        String workerId,
        Clock clock
    ) {
        this.tolerableClientServerClockDifferenceSeconds = tolerableClientServerClockDifferenceSeconds;
        this.httpClient = httpClient;
        this.workerId = workerId;
        this.clock = clock;
        this.objectMapper = new ObjectMapper();
    }

    @FunctionalInterface
    public interface RetryableAction {
        void execute() throws IOException, NonRetryableException, InterruptedException;
    }

    private static void retryWithExponentialBackoff(
            RetryableAction action, int maxRetries, long baseRetryTimeMs, Consumer<Exception> exceptionConsumer) throws InterruptedException, IllegalStateException {
        int attempt = 0;
        while (true) {
            try {
                action.execute();
                break; // Exit if action succeeds
            } catch (NonRetryableException e) {
                Exception underlyingException = (Exception) e.getCause();
                exceptionConsumer.accept(underlyingException);
                throw new IllegalStateException(underlyingException);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                attempt++;
                if (attempt > maxRetries) {
                    exceptionConsumer.accept(e);
                    throw new RetriesExceededException(e, attempt);
                }
                Duration sleepDuration = Duration.ofMillis((long) (Math.pow(2.0, attempt - 1.0) * baseRetryTimeMs));
                log.atWarn().setCause(e)
                        .setMessage("Couldn't complete action due to exception. Backing off {} and trying again.")
                        .addArgument(sleepDuration).log();
                Thread.sleep(sleepDuration.toMillis());
            }
        }
    }

    public void setup(Supplier<IWorkCoordinationContexts.IInitializeCoordinatorStateContext> contextSupplier)
        throws IOException, InterruptedException {
        var body = "{\n"
            + "  \"settings\": {\n"
            + "   \"index\": {"
            + "    \"number_of_shards\": 1,\n"
            + "    \"number_of_replicas\": 1\n"
            + "   }\n"
            + "  },\n"
            + "  \"mappings\": {\n"
            + "    \"properties\": {\n"
            + "      \"" + EXPIRATION_FIELD_NAME + "\": {\n"
            + "        \"type\": \"long\"\n"
            + "      },\n"
            + "      \"" + COMPLETED_AT_FIELD_NAME + "\": {\n"
            + "        \"type\": \"long\"\n"
            + "      },\n"
            + "      \"leaseHolderId\": {\n"
            + "        \"type\": \"keyword\",\n"
            + "        \"norms\": false\n"
            + "      },\n"
            + "      \"status\": {\n"
            + "        \"type\": \"keyword\",\n"
            + "        \"norms\": false\n"
            + "      },\n"
            + "     \"" + SUCCESSOR_ITEMS_FIELD_NAME + "\": {\n"
            + "       \"type\": \"keyword\",\n"
            + "        \"norms\": false\n"
            + "      }\n"
            + "    }\n"
            + "  }\n"
            + "}\n";

        try {
            doUntil("setup-" + INDEX_NAME, 100, MAX_SETUP_RETRIES, contextSupplier::get, () -> {
                try {
                    var indexCheckResponse = httpClient.makeJsonRequest(
                        AbstractedHttpClient.HEAD_METHOD,
                        INDEX_NAME,
                        null,
                        null
                    );
                    if (indexCheckResponse.getStatusCode() == 200) {
                        log.info("Not creating " + INDEX_NAME + " because it already exists");
                        return indexCheckResponse;
                    }
                    log.atInfo().setMessage("Creating {} because HEAD returned {}")
                        .addArgument(INDEX_NAME)
                        .addArgument(indexCheckResponse::getStatusCode)
                        .log();
                    return httpClient.makeJsonRequest(AbstractedHttpClient.PUT_METHOD, INDEX_NAME, null, body);
                } catch (Exception e) {
                    throw Lombok.sneakyThrow(e);
                }
            }, r -> new Object() {
                @Override
                @SneakyThrows
                public String toString() {
                    var payloadStr = Optional.ofNullable(r.getPayloadBytes())
                        .map(bytes -> (new String(bytes, StandardCharsets.UTF_8)))
                        .orElse("[NULL]");
                    return "[ statusCode: " + r.getStatusCode() + ", payload: " + payloadStr + "]";
                }
            }, (response, ignored) -> (response.getStatusCode() / 100) == 2);
        } catch (MaxTriesExceededException e) {
            throw new IOException(e);
        }
    }

    enum DocumentModificationResult {
        IGNORED,
        CREATED,
        UPDATED;

        static DocumentModificationResult parse(String s) {
            switch (Optional.ofNullable(s).orElse("")/*let default handle this*/) {
                case "noop":
                    return DocumentModificationResult.IGNORED;
                case "created":
                    return DocumentModificationResult.CREATED;
                case UPDATED_COUNT_FIELD_NAME:
                    return DocumentModificationResult.UPDATED;
                default:
                    throw new IllegalArgumentException("Unknown result " + s);
            }
        }
    }

    AbstractedHttpClient.AbstractHttpResponse createOrUpdateLeaseForDocument(
        String workItemId,
        long expirationWindowSeconds
    ) throws IOException {
        // the notion of 'now' isn't supported with painless scripts
        // https://www.elastic.co/guide/en/elasticsearch/painless/current/painless-datetime.html#_datetime_now
        final var upsertLeaseBodyTemplate = "{\n"
            + "  \"scripted_upsert\": true,\n"
            + "  \"upsert\": {\n"
            + "    \"scriptVersion\": \"" + SCRIPT_VERSION_TEMPLATE + "\",\n"
            + "    \"" + EXPIRATION_FIELD_NAME + "\": 0,\n"
            + "    \"creatorId\": \"" + WORKER_ID_TEMPLATE + "\",\n"
            + "    \"numAttempts\": 0\n"
            + "  },\n"
            + "  \"script\": {\n"
            + "    \"lang\": \"painless\",\n"
            + "    \"params\": { \n"
            + "      \"clientTimestamp\": " + CLIENT_TIMESTAMP_TEMPLATE + ",\n"
            + "      \"expirationWindow\": " + EXPIRATION_WINDOW_TEMPLATE + ",\n"
            + "      \"workerId\": \"" + WORKER_ID_TEMPLATE + "\"\n"
            + "    },\n"
            + "    \"source\": \""
            + "      if (ctx._source.scriptVersion != \\\"" + SCRIPT_VERSION_TEMPLATE + "\\\") {"
            + "        throw new IllegalArgumentException(\\\"scriptVersion mismatch.  Not all participants are using the same script: sourceVersion=\\\" + ctx.source.scriptVersion);"
            + "      } "
            + "      long serverTimeSeconds = System.currentTimeMillis() / 1000;"
            + "      if (Math.abs(params.clientTimestamp - serverTimeSeconds) > {CLOCK_DEVIATION_SECONDS_THRESHOLD}) {"
            + "        throw new IllegalArgumentException(\\\"The current times indicated between the client and server are too different.\\\");"
            + "      }"
            + "      long newExpiration = params.clientTimestamp + (((long)Math.pow(2, ctx._source.numAttempts)) * params.expirationWindow);"
            + "      if (params.expirationWindow > 0 && ctx._source." + COMPLETED_AT_FIELD_NAME + " == null) {"
            +          // work item is not completed, but may be assigned to this or a different worker (or unassigned)
            "          if (ctx._source." + LEASE_HOLDER_ID_FIELD_NAME + " == params.workerId && "
            + "            ctx._source." + EXPIRATION_FIELD_NAME + " > serverTimeSeconds) {"
            +            // count as an update to force the caller to lookup the expiration time, but no need to modify it
            "            ctx.op = \\\"update\\\";"
            + "        } else if (ctx._source." + EXPIRATION_FIELD_NAME + " < serverTimeSeconds && " + // is expired
            "                     ctx._source." + EXPIRATION_FIELD_NAME + " < newExpiration) {" +      // sanity check
            "            ctx._source." + EXPIRATION_FIELD_NAME + " = newExpiration;"
            + "          ctx._source." + LEASE_HOLDER_ID_FIELD_NAME + " = params.workerId;"
            + "          ctx._source.numAttempts += 1;"
            + "        } else {"
            + "          ctx.op = \\\"noop\\\";"
            + "        }"
            + "      } else if (params.expirationWindow != 0) {"
            + "        ctx.op = \\\"noop\\\";"
            + "      }"
            + "\"\n"
            + "  }\n"
            + // close script
            "}"; // close top-level

        var body = upsertLeaseBodyTemplate.replace(SCRIPT_VERSION_TEMPLATE, "poc")
            .replace(WORKER_ID_TEMPLATE, workerId)
            .replace(CLIENT_TIMESTAMP_TEMPLATE, Long.toString(clock.instant().toEpochMilli() / 1000))
            .replace(EXPIRATION_WINDOW_TEMPLATE, Long.toString(expirationWindowSeconds))
            .replace(
                CLOCK_DEVIATION_SECONDS_THRESHOLD_TEMPLATE,
                Long.toString(tolerableClientServerClockDifferenceSeconds)
            );

        return httpClient.makeJsonRequest(
            AbstractedHttpClient.POST_METHOD,
            INDEX_NAME + "/_update/" + workItemId,
            null,
            body
        );
    }

    DocumentModificationResult getResult(AbstractedHttpClient.AbstractHttpResponse response) throws IOException {
        if (response.getStatusCode() == 409) {
            return DocumentModificationResult.IGNORED;
        }
        final var resultDoc = objectMapper.readTree(response.getPayloadBytes());
        var resultStr = resultDoc.path(RESULT_OPENSSEARCH_FIELD_NAME).textValue();
        try {
            return DocumentModificationResult.parse(resultStr);
        } catch (Exception e) {
            log.atWarn().setCause(e).setMessage("Caught exception while parsing the response").log();
            log.atWarn().setMessage("status: {} {}")
                .addArgument(response::getStatusCode)
                .addArgument(response::getStatusText)
                .log();
            log.atWarn().setMessage("headers: {}")
                .addArgument(() -> response.getHeaders()
                    .map(kvp->kvp.getKey() + ":" + kvp.getValue()).collect(Collectors.joining("\n")))
                .log();
            log.atWarn().setMessage("Payload: {}")
                .addArgument(() -> {
                        try {
                            return new String(response.getPayloadBytes(), StandardCharsets.UTF_8);
                        } catch (Exception e2) {
                            return "EXCEPTION: while trying to display response bytes: " + e2;
                        }
                    }
                )
                .log();
            throw e;
        }
    }

    @Override
    public boolean createUnassignedWorkItem(
        String workItemId,
        Supplier<IWorkCoordinationContexts.ICreateUnassignedWorkItemContext> contextSupplier
    ) throws IOException {
        try (var ctx = contextSupplier.get()) {
            var response = createOrUpdateLeaseForDocument(workItemId, 0);
            return getResult(response) == DocumentModificationResult.CREATED;
        }
    }

    private ArrayList<String> getSuccessorItemsIfPresent(JsonNode responseDoc) {
        if (responseDoc.has(SUCCESSOR_ITEMS_FIELD_NAME)) {
            return new ArrayList<>(Arrays.asList(responseDoc.get(SUCCESSOR_ITEMS_FIELD_NAME).asText().split(SUCCESSOR_ITEM_DELIMITER)));
        }
        return null;
    }

    @Override
    @NonNull
    public WorkAcquisitionOutcome createOrUpdateLeaseForWorkItem(
        String workItemId,
        Duration leaseDuration,
        Supplier<IWorkCoordinationContexts.IAcquireSpecificWorkContext> contextSupplier
    ) throws IOException, InterruptedException {
        try (var ctx = contextSupplier.get()) {
            var startTime = Instant.now();
            var updateResponse = createOrUpdateLeaseForDocument(workItemId, leaseDuration.toSeconds());
            var resultFromUpdate = getResult(updateResponse);

            if (resultFromUpdate == DocumentModificationResult.CREATED) {
                return new WorkItemAndDuration(workItemId, startTime.plus(leaseDuration));
            } else {
                final var httpResponse = httpClient.makeJsonRequest(
                    AbstractedHttpClient.GET_METHOD,
                    INDEX_NAME + "/_doc/" + workItemId,
                    null,
                    null
                );
                final var responseDoc = objectMapper.readTree(httpResponse.getPayloadBytes()).path(SOURCE_FIELD_NAME);
                if (resultFromUpdate == DocumentModificationResult.UPDATED) {
                    var leaseExpirationTime = Instant.ofEpochMilli(1000 * responseDoc.path(EXPIRATION_FIELD_NAME).longValue());
                    return new WorkItemAndDuration(workItemId, leaseExpirationTime);
                } else if (!responseDoc.path(COMPLETED_AT_FIELD_NAME).isMissingNode()) {
                    return new AlreadyCompleted();
                } else if (resultFromUpdate == DocumentModificationResult.IGNORED) {
                    throw new LeaseLockHeldElsewhereException();
                } else {
                    throw new IllegalStateException("Unknown result: " + resultFromUpdate);
                }
            }
        }
    }

    public void completeWorkItem(
        String workItemId,
        Supplier<IWorkCoordinationContexts.ICompleteWorkItemContext> contextSupplier
    ) throws IOException, InterruptedException {
        try (var ctx = contextSupplier.get()) {
            final var markWorkAsCompleteBodyTemplate = "{\n"
                + "  \"script\": {\n"
                + "    \"lang\": \"painless\",\n"
                + "    \"params\": { \n"
                + "      \"clientTimestamp\": " + CLIENT_TIMESTAMP_TEMPLATE + ",\n"
                + "      \"workerId\": \"" + WORKER_ID_TEMPLATE + "\"\n"
                + "    },\n"
                + "    \"source\": \""
                + "      if (ctx._source.scriptVersion != \\\"" + SCRIPT_VERSION_TEMPLATE + "\\\") {"
                + "        throw new IllegalArgumentException(\\\"scriptVersion mismatch.  Not all participants are using the same script: sourceVersion=\\\" + ctx.source.scriptVersion);"
                + "      } "
                + "      if (ctx._source." + LEASE_HOLDER_ID_FIELD_NAME + " != params.workerId) {"
                + "        throw new IllegalArgumentException(\\\"work item was owned by \\\" + ctx._source."
                +                        LEASE_HOLDER_ID_FIELD_NAME + " + \\\" not \\\" + params.workerId);"
                + "      } else {"
                + "        ctx._source." + COMPLETED_AT_FIELD_NAME + " = System.currentTimeMillis() / 1000;"
                + "     }"
                + "\"\n"
                + "  }\n"
                + "}";

            var body = markWorkAsCompleteBodyTemplate.replace(SCRIPT_VERSION_TEMPLATE, "poc")
                .replace(WORKER_ID_TEMPLATE, workerId)
                .replace(CLIENT_TIMESTAMP_TEMPLATE, Long.toString(clock.instant().toEpochMilli() / 1000));

            var response = httpClient.makeJsonRequest(
                AbstractedHttpClient.POST_METHOD,
                INDEX_NAME + "/_update/" + workItemId,
                null,
                body
            );
            final var resultStr = objectMapper.readTree(response.getPayloadBytes())
                .get(RESULT_OPENSSEARCH_FIELD_NAME)
                .textValue();
            if (DocumentModificationResult.UPDATED != DocumentModificationResult.parse(resultStr)) {
                throw new IllegalStateException(
                    "Unexpected response for workItemId: "
                        + workItemId
                        + ".  Response: "
                        + response.toDiagnosticString()
                );
            }
            refresh(ctx::getRefreshContext);
        }
    }

    private int numWorkItemsNotYetCompleteInternal(
        Supplier<IWorkCoordinationContexts.IPendingWorkItemsContext> contextSupplier
    ) throws IOException, InterruptedException {
        try (var context = contextSupplier.get()) {
            refresh(context::getRefreshContext);
            final var queryBody = "{\n"
                + "\"query\": {"
                + "  \"bool\": {"
                + "    \"must\": ["
                + "      { \"exists\":"
                + "        { \"field\": \"" + EXPIRATION_FIELD_NAME + "\"}"
                + "      }"
                + "    ],"
                + "    \"must_not\": ["
                + "      { \"exists\":"
                + "        { \"field\": \"" + COMPLETED_AT_FIELD_NAME + "\"}"
                + "      }"
                + "    ]"
                + "  }"
                + "},"
                + "\"size\": 0" // This sets the number of items to include in the `hits.hits` array, but doesn't affect
                + "}";          // the integer value in `hits.total.value`

            var path = INDEX_NAME + "/_search";
            var response = httpClient.makeJsonRequest(AbstractedHttpClient.POST_METHOD, path, null, queryBody);
            var statusCode = response.getStatusCode();
            if (statusCode != 200) {
                throw new IllegalStateException(
                        "Querying for pending (expired or not) work, "
                                + "returned an unexpected status code "
                                + statusCode
                                + " instead of 200"
                );
            }
            var payload = objectMapper.readTree(response.getPayloadBytes());
            var totalHits = payload.path("hits").path("total").path("value").asInt();
            // In the case where totalHits is 0, we need to be particularly sure that we're not missing data. The `relation`
            // for the total must be `eq` or we need to throw an error because it's not safe to rely on this data.
            if (totalHits == 0 && !payload.path("hits").path("total").path("relation").textValue().equals("eq")) {
                throw new IllegalStateException("Querying for notYetCompleted work returned 0 hits with an unexpected total relation.");
            }
            return totalHits;
        }
    }

    @Override
    public int numWorkItemsNotYetComplete(Supplier<IWorkCoordinationContexts.IPendingWorkItemsContext> contextSupplier)
        throws IOException, InterruptedException {
        // This result is not guaranteed to be accurate unless it is 0.  All numbers greater than 0 are a lower bound.
        return numWorkItemsNotYetCompleteInternal(contextSupplier);
    }

    @Override
    public boolean workItemsNotYetComplete(Supplier<IWorkCoordinationContexts.IPendingWorkItemsContext> contextSupplier)
        throws IOException, InterruptedException {
        return numWorkItemsNotYetCompleteInternal(contextSupplier) >= 1;
    }

    enum UpdateResult {
        SUCCESSFUL_ACQUISITION,
        VERSION_CONFLICT,
        NOTHING_TO_ACQUIRE
    }

    /**
     * @param expirationWindowSeconds How long the initial lease should be for
     * @throws IOException if the request couldn't be made
     */
    UpdateResult assignOneWorkItem(long expirationWindowSeconds) throws IOException {
        // the random_score reduces the number of version conflicts from ~1200 for 40 concurrent requests
        // to acquire 40 units of work to around 800
        final var queryUpdateTemplate = "{\n"
            + "\"query\": {"
            + "  \"function_score\": {\n" + QUERY_INCOMPLETE_EXPIRED_ITEMS_STR + ","
            + "    \"random_score\": {},\n"
            + "    \"boost_mode\": \"replace\"\n" + // Try to avoid the workers fighting for the same work items
            "  }"
            + "},"
            + "\"size\": 1,\n"
            + "\"script\": {"
            + "  \"params\": { \n"
            + "    \"clientTimestamp\": " + CLIENT_TIMESTAMP_TEMPLATE + ",\n"
            + "    \"expirationWindow\": " + EXPIRATION_WINDOW_TEMPLATE + ",\n"
            + "    \"workerId\": \"" + WORKER_ID_TEMPLATE + "\",\n"
            + "    \"counter\": 0\n"
            + "  },\n"
            + "  \"source\": \""
            + "      if (ctx._source.scriptVersion != \\\"" + SCRIPT_VERSION_TEMPLATE + "\\\") {"
            + "        throw new IllegalArgumentException(\\\"scriptVersion mismatch.  Not all participants are using the same script: sourceVersion=\\\" + ctx.source.scriptVersion);"
            + "      } "
            + "      long serverTimeSeconds = System.currentTimeMillis() / 1000;"
            + "      if (Math.abs(params.clientTimestamp - serverTimeSeconds) > {CLOCK_DEVIATION_SECONDS_THRESHOLD}) {"
            + "        throw new IllegalArgumentException(\\\"The current times indicated between the client and server are too different.\\\");"
            + "      }"
            + "      long newExpiration = params.clientTimestamp + (((long)Math.pow(2, ctx._source.numAttempts)) * params.expirationWindow);"
            + "      if (ctx._source." + EXPIRATION_FIELD_NAME + " < serverTimeSeconds && " + // is expired
            "          ctx._source." + EXPIRATION_FIELD_NAME + " < newExpiration) {" +        // sanity check
            "        ctx._source." + EXPIRATION_FIELD_NAME + " = newExpiration;"
            + "        ctx._source." + LEASE_HOLDER_ID_FIELD_NAME + " = params.workerId;"
            + "        ctx._source.numAttempts += 1;"
            + "      } else {"
            + "        ctx.op = \\\"noop\\\";"
            + "      }"
            + "\" "
            +  // end of source script contents
            "}"
            +    // end of script block
            "}";

        final var timestampEpochSeconds = clock.instant().toEpochMilli() / 1000;
        final var body = queryUpdateTemplate.replace(SCRIPT_VERSION_TEMPLATE, "poc")
            .replace(WORKER_ID_TEMPLATE, workerId)
            .replace(CLIENT_TIMESTAMP_TEMPLATE, Long.toString(timestampEpochSeconds))
            .replace(OLD_EXPIRATION_THRESHOLD_TEMPLATE, Long.toString(timestampEpochSeconds))
            .replace(EXPIRATION_WINDOW_TEMPLATE, Long.toString(expirationWindowSeconds))
            .replace(
                CLOCK_DEVIATION_SECONDS_THRESHOLD_TEMPLATE,
                Long.toString(tolerableClientServerClockDifferenceSeconds)
            );

        var response = httpClient.makeJsonRequest(
            AbstractedHttpClient.POST_METHOD,
            INDEX_NAME + "/_update_by_query?refresh=true&max_docs=1",
            null,
            body
        );
        if (response.getStatusCode() == 409) {
            return UpdateResult.VERSION_CONFLICT;
        }
        var resultTree = objectMapper.readTree(response.getPayloadBytes());
        final var numUpdated = resultTree.path(UPDATED_COUNT_FIELD_NAME).longValue();
        final var noops = resultTree.path("noops").longValue();
        if (numUpdated > 1) {
            throw new IllegalStateException("Updated leases for " + numUpdated + " work items instead of 0 or 1");
        }
        if (numUpdated > 0) {
            return UpdateResult.SUCCESSFUL_ACQUISITION;
        } else if (resultTree.path(VERSION_CONFLICTS_FIELD_NAME).longValue() > 0) {
            return UpdateResult.VERSION_CONFLICT;
        } else if (resultTree.path("total").longValue() == 0) {
            return UpdateResult.NOTHING_TO_ACQUIRE;
        } else if (noops > 0) {
            throw new PotentialClockDriftDetectedException(
                "Found " + noops + " noop values in response with no successful updates",
                timestampEpochSeconds
            );
        } else {
            throw new IllegalStateException("Unexpected response for update: " + resultTree);
        }
    }

    private WorkItemWithPotentialSuccessors getAssignedWorkItemUnsafe()
        throws IOException, AssignedWorkDocumentNotFoundException, MalformedAssignedWorkDocumentException {
        final var queryWorkersAssignedItemsTemplate = "{\n"
            + "  \"query\": {\n"
            + "    \"bool\": {"
            + "      \"must\": ["
            + "        {"
            + "          \"term\": { \"" + LEASE_HOLDER_ID_FIELD_NAME + "\": \"" + WORKER_ID_TEMPLATE + "\"}\n"
            + "        }"
            + "      ],"
            + "      \"must_not\": ["
            + "        {"
            + "          \"exists\": { \"field\": \"" + COMPLETED_AT_FIELD_NAME + "\"}\n"
            + "        }"
            + "      ]"
            + "    }"
            + "  }"
            + "}";
        final var body = queryWorkersAssignedItemsTemplate.replace(WORKER_ID_TEMPLATE, workerId);
        var response = httpClient.makeJsonRequest(
            AbstractedHttpClient.POST_METHOD,
            INDEX_NAME + "/_search",
            null,
            body
        );

        if (response.getStatusCode() >= 400) {
            throw new AssignedWorkDocumentNotFoundException(response);
        }

        final var resultHitsUpper = objectMapper.readTree(response.getPayloadBytes()).path("hits");
        if (resultHitsUpper.isMissingNode()) {
            log.warn("Couldn't find the top level 'hits' field, returning null");
            return null;
        }
        final var numDocs = resultHitsUpper.path("total").path("value").longValue();
        if (numDocs == 0) {
            throw new AssignedWorkDocumentNotFoundException(response);
        } else if (numDocs != 1) {
            throw new MalformedAssignedWorkDocumentException(response);
        }
        var resultHitInner = resultHitsUpper.path("hits").path(0);
        var expiration = resultHitInner.path(SOURCE_FIELD_NAME).path(EXPIRATION_FIELD_NAME).longValue();
        if (expiration == 0) {
            log.atWarn().setMessage("Expiration wasn't found or wasn't set to > 0 for response: {}")
                .addArgument(response::toDiagnosticString).log();
            throw new MalformedAssignedWorkDocumentException(response);
        }

        var responseDoc = resultHitInner.get(SOURCE_FIELD_NAME);
        var successorItems = getSuccessorItemsIfPresent(responseDoc);
        var rval = new WorkItemWithPotentialSuccessors(resultHitInner.get("_id").asText(), Instant.ofEpochMilli(1000 * expiration), successorItems);
        log.atInfo().setMessage("Returning work item and lease: {}").addArgument(rval).log();
        return rval;
    }

    private WorkItemWithPotentialSuccessors getAssignedWorkItem(LeaseChecker leaseChecker,
                                                    IWorkCoordinationContexts.IAcquireNextWorkItemContext ctx)
        throws RetriesExceededException, InterruptedException
    {
        int malformedDocRetries = 0;
        int transientRetries = 0;
        while (true) {
            try {
                return getAssignedWorkItemUnsafe();
            } catch (MalformedAssignedWorkDocumentException | IOException | AssignedWorkDocumentNotFoundException e) {
                int retries;
                if (e instanceof  MalformedAssignedWorkDocumentException) {
                    // This probably isn't a recoverable error, but since we think that we might have the lease,
                    // there's no reason to not try at least a few times
                    if (malformedDocRetries > MAX_MALFORMED_ASSIGNED_WORK_DOC_RETRIES) {
                        ctx.addTraceException(e, true);
                        log.atError().setCause(e).setMessage(
                            "Throwing exception because max tries (" + MAX_MALFORMED_ASSIGNED_WORK_DOC_RETRIES + ")" +
                                " have been exhausted").log();
                        throw new RetriesExceededException(e, malformedDocRetries);
                    }
                    retries = ++malformedDocRetries;
                } else {
                    retries = ++transientRetries;
                }

                ctx.addTraceException(e, false);
                var sleepBeforeNextRetryDuration = Duration.ofMillis(
                    Math.min(MAX_ASSIGNED_DOCUMENT_NOT_FOUND_RETRY_INTERVAL,
                        (long) (Math.pow(2.0, (retries-1)) * ACQUIRE_WORK_RETRY_BASE_MS)));
                leaseChecker.checkRetryWaitTimeOrThrow(e, retries-1, sleepBeforeNextRetryDuration);

                log.atWarn().setCause(e)
                    .setMessage("Couldn't complete work assignment due to exception. Backing off {} and trying again.")
                    .addArgument(sleepBeforeNextRetryDuration).log();
                Thread.sleep(sleepBeforeNextRetryDuration.toMillis());
            }
        }
    }

    private void updateWorkItemWithSuccessors(String workItemId, ArrayList<String> successorWorkItemIds) throws IOException, NonRetryableException {
        final var updateSuccessorWorkItemsTemplate = "{\n"
                + "  \"script\": {\n"
                + "    \"lang\": \"painless\",\n"
                + "    \"params\": { \n"
                + "      \"clientTimestamp\": " + CLIENT_TIMESTAMP_TEMPLATE + ",\n"
                + "      \"workerId\": \"" + WORKER_ID_TEMPLATE + "\",\n"
                + "      \"successorWorkItems\": \"" + SUCCESSOR_WORK_ITEM_IDS_TEMPLATE + "\"\n"
                + "    },\n"
                + "    \"source\": \""
                + "      if (ctx._source.scriptVersion != \\\"" + SCRIPT_VERSION_TEMPLATE + "\\\") {"
                + "        throw new IllegalArgumentException(\\\"scriptVersion mismatch.  Not all participants are using the same script: sourceVersion=\\\" + ctx.source.scriptVersion);"
                + "      }"
                + "      if (ctx._source." + LEASE_HOLDER_ID_FIELD_NAME + " != params.workerId) {"
                + "        throw new IllegalArgumentException(\\\"work item was owned by \\\" + ctx._source."
                +                        LEASE_HOLDER_ID_FIELD_NAME + " + \\\" not \\\" + params.workerId);"
                + "      }"
                + "      if (ctx._source." + SUCCESSOR_ITEMS_FIELD_NAME + " != null && ctx._source." + SUCCESSOR_ITEMS_FIELD_NAME + " != params.successorWorkItems) {"
                + "        throw new IllegalArgumentException(\\\"The " + SUCCESSOR_ITEMS_FIELD_NAME + " field cannot be updated with a different value.\\\")"
                + "      }"
                + "      ctx._source." + SUCCESSOR_ITEMS_FIELD_NAME + " = params.successorWorkItems;"
                + "\"\n"
                + "  }\n"
                + "}";

        var body = updateSuccessorWorkItemsTemplate.replace(SCRIPT_VERSION_TEMPLATE, "poc")
                .replace(WORKER_ID_TEMPLATE, workerId)
                .replace(CLIENT_TIMESTAMP_TEMPLATE, Long.toString(clock.instant().toEpochMilli() / 1000))
                .replace(SUCCESSOR_WORK_ITEM_IDS_TEMPLATE, String.join(SUCCESSOR_ITEM_DELIMITER, successorWorkItemIds));

        var response = httpClient.makeJsonRequest(
                AbstractedHttpClient.POST_METHOD,
                INDEX_NAME + "/_update/" + workItemId,
                null,
                body
        );
        try {
            DocumentModificationResult modificationResult = getResult(response);
            if (DocumentModificationResult.UPDATED != modificationResult) {
                throw new IllegalStateException(
                        "Unexpected response for workItemId: "
                                + workItemId
                                + ".  Response: "
                                + response.toDiagnosticString()
                );
            }
        } catch (IllegalArgumentException e) {
            var resultTree = objectMapper.readTree(response.getPayloadBytes());
            if (resultTree.has("error") &&
                    resultTree.get("error").has("type") &&
                    resultTree.get("error").get("type").asText().equals("illegal_argument_exception")) {
                throw new NonRetryableException(new IllegalArgumentException(resultTree.get("error").get("caused_by").asText()));
            }
            throw new IllegalStateException(
                    "Unexpected response for workItemId: "
                            + workItemId
                            + ".  Response: "
                            + response.toDiagnosticString()
            );
        }
    }

    // This is an idempotent function to create multiple unassigned work items. It uses the `create` function in the bulk
    // API which creates a document only if the specified ID doesn't yet exist. It is distinct from createUnassignedWorkItem
    // because it is an expected outcome of this function that sometimes the work item is already created. That function
    // uses `createOrUpdateLease`, whereas this function deliberately never modifies an already-existing work item.
    private void createUnassignedWorkItemsIfNonexistent(ArrayList<String> workItemIds) throws IOException, IllegalStateException {
        String workItemBodyTemplate = "{\"numAttempts\":0, \"scriptVersion\":\"" + SCRIPT_VERSION_TEMPLATE + "\", " +
            "\"creatorId\":\"" + WORKER_ID_TEMPLATE + "\", \"" + EXPIRATION_FIELD_NAME + "\":0 }";
        String workItemBody = workItemBodyTemplate.replace(SCRIPT_VERSION_TEMPLATE, "poc").replace(WORKER_ID_TEMPLATE, workerId);

        StringBuilder body = new StringBuilder();
        for (var workItemId : workItemIds) {
            body.append("{\"create\":{\"_id\":\"").append(workItemId).append("\"}}\n");
            body.append(workItemBody).append("\n");
        }
        var response = httpClient.makeJsonRequest(
                AbstractedHttpClient.POST_METHOD,
                INDEX_NAME + "/_bulk",
                null,
                body.toString()
        );
        var statusCode = response.getStatusCode();
        if (statusCode != 200) {
            throw new IllegalStateException(
                    "A bulk request to create successor work item(s), "
                            + String.join(", ", workItemIds)
                            + "returned an unexpected status code "
                            + statusCode
                            + " instead of 200"
            );
        }
        // parse the response body and if any of the writes failed with anything EXCEPT a version conflict, throw an exception
        var resultTree = objectMapper.readTree(response.getPayloadBytes());
        var errors = resultTree.path("errors").asBoolean();
        if (!errors) {
            return;
        }
        // Sometimes these work items have already been created. This is because of the non-transactional nature of OpenSearch
        // as a work coordinator. If a worker crashed/failed after updating the parent task's `successorItems` field, but before
        // completing creation of all the successor items, some of them may already exist. The `create` action in a bulk
        // request will not modify those items, but it will return a 409 CONFLICT response code for them.
        var acceptableStatusCodes = List.of(CREATED_RESPONSE_CODE, CONFLICT_RESPONSE_CODE);

        var resultsIncludeUnacceptableStatusCodes = StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(resultTree.path("items").elements(), 0), false
        ).anyMatch(item -> !acceptableStatusCodes.contains(item.path("create").path("status").asInt()));

        if (resultsIncludeUnacceptableStatusCodes) {
            throw new IllegalStateException(
                    "One or more of the successor work item(s) could not be created: "
                            + String.join(", ", workItemIds)
                            + ".  Response: "
                            + response.toDiagnosticString()
            );
        }

    }

    @Override
    public void createSuccessorWorkItemsAndMarkComplete(
            String workItemId,
            ArrayList<String> successorWorkItemIds,
            Supplier<IWorkCoordinationContexts.ICreateSuccessorWorkItemsContext> contextSupplier
    ) throws IOException, InterruptedException, IllegalStateException {
        if (successorWorkItemIds.contains(workItemId)) {
            throw new IllegalArgumentException(String.format("successorWorkItemIds %s can not not contain the parent workItemId: %s", successorWorkItemIds, workItemId));
        }
        if (successorWorkItemIds.stream().anyMatch(itemId -> itemId.contains(SUCCESSOR_ITEM_DELIMITER))) {
            throw new IllegalArgumentException("successorWorkItemIds can not contain the delimiter: " + SUCCESSOR_ITEM_DELIMITER);
        }
        try (var ctx = contextSupplier.get()) {
            // It is extremely valuable to try hard to get the work item updated with successor item ids. If it fails without
            // completing this step, the next worker to pick up this lease will rerun all of the work. If it fails after this
            // step, the next worker to pick it up will see this update and resume the work of creating the successor work items,
            // without redriving the work.
            retryWithExponentialBackoff(
                    () -> updateWorkItemWithSuccessors(workItemId, successorWorkItemIds),
                    MAX_CREATE_SUCCESSOR_WORK_ITEMS_RETRIES,
                    CREATE_SUCCESSOR_WORK_ITEMS_RETRY_BASE_MS,
                    e -> ctx.addTraceException(e, true)
            );
            retryWithExponentialBackoff(
                    () -> createUnassignedWorkItemsIfNonexistent(successorWorkItemIds),
                    MAX_CREATE_UNASSIGNED_SUCCESSOR_WORK_ITEM_RETRIES,
                    CREATE_SUCCESSOR_WORK_ITEMS_RETRY_BASE_MS,
                    e -> ctx.addTraceException(e, true)
            );
            retryWithExponentialBackoff(
                    () -> completeWorkItem(workItemId, ctx::getCompleteWorkItemContext),
                    MAX_MARK_AS_COMPLETED_RETRIES,
                    CREATE_SUCCESSOR_WORK_ITEMS_RETRY_BASE_MS,
                    e -> ctx.addTraceException(e, true)
            );
            refresh(ctx::getRefreshContext);
        }
    }

    @AllArgsConstructor
    private static class MaxTriesExceededException extends Exception {
        final transient Object suppliedValue;
        final transient Object transformedValue;
    }

    @Getter
    public static class PotentialClockDriftDetectedException extends IllegalStateException {
        public final long timestampEpochSeconds;

        public PotentialClockDriftDetectedException(String s, long timestampEpochSeconds) {
            super(s);
            this.timestampEpochSeconds = timestampEpochSeconds;
        }
    }

    @AllArgsConstructor
    public static class ResponseException extends Exception {
        final transient AbstractedHttpClient.AbstractHttpResponse response;

        @Override
        public String getMessage() {
            var parentPrefix = Optional.ofNullable(super.getMessage()).map(s -> s + " ").orElse("");
            return parentPrefix  + "Response: " + response.toDiagnosticString() ;
        }
    }

    public static class AssignedWorkDocumentNotFoundException extends ResponseException {
        private AssignedWorkDocumentNotFoundException(AbstractedHttpClient.AbstractHttpResponse r) {
            super(r);
        }
    }

    public static class MalformedAssignedWorkDocumentException extends ResponseException {
        public MalformedAssignedWorkDocumentException(AbstractedHttpClient.AbstractHttpResponse response) {
            super(response);
        }
    }

    public static class RetriesExceededException extends IllegalStateException {
        final int retries;

        public RetriesExceededException(Throwable cause, int retries) {
            super(cause);
            this.retries = retries;
        }
    }

    public static class NonRetryableException extends Exception {
        public NonRetryableException(Exception cause) {
            super(cause);
        }
    }

    static <T, U> U doUntil(
        String labelThatShouldBeAContext,
        long initialRetryDelayMs,
        int maxTries,
        Supplier<IWorkCoordinationContexts.IRetryableActivityContext> contextSupplier,
        Supplier<T> supplier,
        Function<T, U> transformer,
        BiPredicate<T, U> test
    ) throws InterruptedException, MaxTriesExceededException {
        var sleepMillis = initialRetryDelayMs;

        try (var context = contextSupplier.get()) {
            for (var attempt = 1; ; ++attempt) {
                var suppliedVal = supplier.get();
                var transformedVal = transformer.apply(suppliedVal);
                if (test.test(suppliedVal, transformedVal)) {
                    return transformedVal;
                } else {
                    log.atWarn().setMessage("Retrying {} because the predicate failed for: ({},{})")
                        .addArgument(labelThatShouldBeAContext)
                        .addArgument(suppliedVal)
                        .addArgument(transformedVal)
                        .log();
                    if (attempt >= maxTries) {
                        context.recordFailure();
                        throw new MaxTriesExceededException(suppliedVal, transformedVal);
                    } else {
                        context.recordRetry();
                    }
                    Thread.sleep(sleepMillis);
                    sleepMillis *= 2;
                }
            }
        }
    }

    private void refresh(Supplier<IWorkCoordinationContexts.IRefreshContext> contextSupplier) throws IOException,
        InterruptedException {
        try {
            doUntil("refresh", 100, MAX_REFRESH_RETRIES, contextSupplier::get, () -> {
                try {
                    return httpClient.makeJsonRequest(
                        AbstractedHttpClient.GET_METHOD,
                        INDEX_NAME + "/_refresh",
                        null,
                        null
                    );
                } catch (IOException e) {
                    throw Lombok.sneakyThrow(e);
                }
            }, AbstractedHttpClient.AbstractHttpResponse::getStatusCode, (r, statusCode) -> statusCode == 200);
        } catch (MaxTriesExceededException e) {
            throw new IOException(e);
        }
    }

    /**
     * @param leaseDuration How long the initial lease should be for OR if we have an issue
     *                      determining which work item we've got, but suspect that we may have
     *                      been assigned a work item, this value will be the maximum time that
     *                      the method spends retrying.
     * @return NoAvailableWorkToBeDone if all of the work items are being held by other processes or if all
     * work has been completed.  An additional check to workItemsArePending() is required to disambiguate.
     * @throws IOException thrown if the initial refresh or update threw.  If there was a chance of work
     * item assignment, IOExceptions will be retried for the remainder of the leaseDuration.
     * @throws InterruptedException if the sleep() call that is waiting for the next retry is interrupted.
     */
    public WorkAcquisitionOutcome
    acquireNextWorkItem(Duration leaseDuration,
                        Supplier<IWorkCoordinationContexts.IAcquireNextWorkItemContext> contextSupplier)
        throws RetriesExceededException, IOException, InterruptedException
    {
        try (var ctx = contextSupplier.get()) {
            refresh(ctx::getRefreshContext);
            final var leaseChecker = new LeaseChecker(leaseDuration, System.nanoTime());
            int driftRetries = 0;
            while (true) {
                Duration sleepBeforeNextRetryDuration;
                try {
                    final var obtainResult = assignOneWorkItem(leaseDuration.toSeconds());
                    switch (obtainResult) {
                        case SUCCESSFUL_ACQUISITION:
                            ctx.recordAssigned();
                            var workItem = getAssignedWorkItem(leaseChecker, ctx);
                            if (workItem.successorWorkItemIds != null) {
                                // continue the previous work of creating the successors and marking this item as completed.
                                createSuccessorWorkItemsAndMarkComplete(workItem.workItemId, workItem.successorWorkItemIds, ctx::getCreateSuccessorWorkItemsContext);
                                // this item is not acquirable, so repeat the loop to find a new item.
                                continue;
                            }
                            return new WorkItemAndDuration(workItem.workItemId, workItem.leaseExpirationTime);
                        case NOTHING_TO_ACQUIRE:
                            ctx.recordNothingAvailable();
                            return new NoAvailableWorkToBeDone();
                        case VERSION_CONFLICT:
                            ctx.recordRetry();
                            continue;
                        default:
                            throw new IllegalStateException(
                                "unknown result from the assignOneWorkItem: " + obtainResult
                            );
                    }
                } catch (PotentialClockDriftDetectedException e) {
                    if (driftRetries >= MAX_DRIFT_RETRIES) {
                        ctx.addTraceException(e, true);
                        ctx.recordFailure(e);
                        throw new RetriesExceededException(e, MAX_DRIFT_RETRIES);
                    } else {
                        ctx.addTraceException(e, false);
                        ctx.recordRecoverableClockError();
                        sleepBeforeNextRetryDuration =
                            Duration.ofMillis((long) (Math.pow(2.0, driftRetries) * ACQUIRE_WORK_RETRY_BASE_MS));
                        leaseChecker.checkRetryWaitTimeOrThrow(e, driftRetries, sleepBeforeNextRetryDuration);
                    }
                    ++driftRetries;
                    log.atInfo().setCause(e)
                        .setMessage("Couldn't complete work assignment due to exception. Backing off {} and retrying.")
                        .addArgument(sleepBeforeNextRetryDuration).log();
                    Thread.sleep(sleepBeforeNextRetryDuration.toMillis());
                }
            }
        }
    }
}
