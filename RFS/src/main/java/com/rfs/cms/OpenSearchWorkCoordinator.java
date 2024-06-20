package com.rfs.cms;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Lombok;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

@Slf4j
public class OpenSearchWorkCoordinator implements IWorkCoordinator {
    public static final String INDEX_NAME = ".migrations_working_state";
    public static final int MAX_RETRIES = 6; // at 100ms, the total delay will be 105s

    public static final String PUT_METHOD = "PUT";
    public static final String POST_METHOD = "POST";
    public static final String GET_METHOD = "GET";
    public static final String HEAD_METHOD = "HEAD";

    public static final String SCRIPT_VERSION_TEMPLATE = "{SCRIPT_VERSION}";
    public static final String WORKER_ID_TEMPLATE = "{WORKER_ID}";
    public static final String CLIENT_TIMESTAMP_TEMPLATE = "{CLIENT_TIMESTAMP}";
    public static final String EXPIRATION_WINDOW_TEMPLATE = "{EXPIRATION_WINDOW}";
    public static final String CLOCK_DEVIATION_SECONDS_THRESHOLD_TEMPLATE = "{CLOCK_DEVIATION_SECONDS_THRESHOLD}";
    public static final String OLD_EXPIRATION_THRESHOLD_TEMPLATE = "OLD_EXPIRATION_THRESHOLD";

    public static final String RESULT_OPENSSEARCH_FIELD_NAME = "result";
    public static final String EXPIRATION_FIELD_NAME = "expiration";
    public static final String UPDATED_COUNT_FIELD_NAME = "updated";
    public static final String LEASE_HOLDER_ID_FIELD_NAME = "leaseHolderId";
    public static final String VERSION_CONFLICTS_FIELD_NAME = "version_conflicts";
    public static final String COMPLETED_AT_FIELD_NAME = "completedAt";
    public static final String SOURCE_FIELD_NAME = "_source";
    public static final String ERROR_OPENSEARCH_FIELD_NAME = "error";

    private final long tolerableClientServerClockDifferenceSeconds;
    private final AbstractedHttpClient httpClient;
    private final String workerId;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OpenSearchWorkCoordinator(AbstractedHttpClient httpClient, long tolerableClientServerClockDifferenceSeconds,
                                     String workerId) {
        this(httpClient, tolerableClientServerClockDifferenceSeconds, workerId, Clock.systemUTC());
    }

    public OpenSearchWorkCoordinator(AbstractedHttpClient httpClient, long tolerableClientServerClockDifferenceSeconds,
                                     String workerId, Clock clock) {
        this.tolerableClientServerClockDifferenceSeconds = tolerableClientServerClockDifferenceSeconds;
        this.httpClient = httpClient;
        this.workerId = workerId;
        this.clock = clock;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void close() throws Exception {
        httpClient.close();
    }

    public void setup() throws IOException, InterruptedException {
        var indexCheckResponse = httpClient.makeJsonRequest(HEAD_METHOD, INDEX_NAME, null, null);
        if (indexCheckResponse.getStatusCode() == 200) {
            log.info("Not creating " + INDEX_NAME + " because it already exists");
            return;
        }
        log.atInfo().setMessage("Creating " + INDEX_NAME + " because it's HEAD check returned " +
                indexCheckResponse.getStatusCode()).log();
        var body = "{\n" +
                "  \"settings\": {\n" +
                "   \"index\": {" +
                "    \"number_of_shards\": 1,\n" +
                "    \"number_of_replicas\": 1\n" +
                "   }\n" +
                "  },\n" +
                "  \"mappings\": {\n" +
                "    \"properties\": {\n" +
                "      \"" + EXPIRATION_FIELD_NAME + "\": {\n" +
                "        \"type\": \"long\"\n" +
                "      },\n" +
                "      \"" + COMPLETED_AT_FIELD_NAME + "\": {\n" +
                "        \"type\": \"long\"\n" +
                "      },\n" +
                "      \"leaseHolderId\": {\n" +
                "        \"type\": \"keyword\",\n" +
                "        \"norms\": false\n" +
                "      },\n" +
                "      \"status\": {\n" +
                "        \"type\": \"keyword\",\n" +
                "        \"norms\": false\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}\n";

        try {
            doUntil("setup-" + INDEX_NAME, 100, MAX_RETRIES,
                    () -> {
                        try {
                            return httpClient.makeJsonRequest(PUT_METHOD, INDEX_NAME, null, body);
                        } catch (Exception e) {
                            throw Lombok.sneakyThrow(e);
                        }
                    },
                    r -> new Object() {
                        @Override
                        @SneakyThrows
                        public String toString() {
                            var payloadStr = Optional.ofNullable(r.getPayloadBytes())
                                    .map(bytes -> (new String(bytes, StandardCharsets.UTF_8))).orElse("[NULL]");
                            return "[ statusCode: " + r.getStatusCode() + ", payload: " + payloadStr + "]";
                        }
                    },
                    (response, ignored) -> (response.getStatusCode() / 100) != 2);
        } catch (MaxTriesExceededException e) {
            throw new IOException(e);
        }
    }

    enum DocumentModificationResult {
        IGNORED, CREATED, UPDATED;
        static DocumentModificationResult parse(String s) {
            switch (Optional.ofNullable(s).orElse("")/*let default handle this*/) {
                case "noop": return DocumentModificationResult.IGNORED;
                case "created": return DocumentModificationResult.CREATED;
                case UPDATED_COUNT_FIELD_NAME: return DocumentModificationResult.UPDATED;
                default:
                    throw new IllegalArgumentException("Unknown result " + s);
            }
        }
    }

    AbstractedHttpClient.AbstractHttpResponse createOrUpdateLeaseForDocument(String workItemId,
                                                                             long expirationWindowSeconds)
            throws IOException {
        // the notion of 'now' isn't supported with painless scripts
        // https://www.elastic.co/guide/en/elasticsearch/painless/current/painless-datetime.html#_datetime_now
        final var upsertLeaseBodyTemplate = "{\n" +
                "  \"scripted_upsert\": true,\n" +
                "  \"upsert\": {\n" +
                "    \"scriptVersion\": \"" + SCRIPT_VERSION_TEMPLATE + "\",\n" +
                "    \"" + EXPIRATION_FIELD_NAME + "\": 0,\n" +
                "    \"creatorId\": \"" + WORKER_ID_TEMPLATE + "\",\n" +
                "    \"numAttempts\": 0\n" +
                "  },\n" +
                "  \"script\": {\n" +
                "    \"lang\": \"painless\",\n" +
                "    \"params\": { \n" +
                "      \"clientTimestamp\": " + CLIENT_TIMESTAMP_TEMPLATE + ",\n" +
                "      \"expirationWindow\": " + EXPIRATION_WINDOW_TEMPLATE + ",\n" +
                "      \"workerId\": \"" + WORKER_ID_TEMPLATE + "\"\n" +
                "    },\n" +
                "    \"source\": \"" +
                "      if (ctx._source.scriptVersion != \\\"" + SCRIPT_VERSION_TEMPLATE + "\\\") {" +
                "        throw new IllegalArgumentException(\\\"scriptVersion mismatch.  Not all participants are using the same script: sourceVersion=\\\" + ctx.source.scriptVersion);" +
                "      } " +
                "      long serverTimeSeconds = System.currentTimeMillis() / 1000;" +
                "      if (Math.abs(params.clientTimestamp - serverTimeSeconds) > {CLOCK_DEVIATION_SECONDS_THRESHOLD}) {" +
                "        throw new IllegalArgumentException(\\\"The current times indicated between the client and server are too different.\\\");" +
                "      }" +
                "      long newExpiration = params.clientTimestamp + (((long)Math.pow(2, ctx._source.numAttempts)) * params.expirationWindow);" +
                "      if (params.expirationWindow > 0 && " +                 // don't obtain a lease lock
                "          ctx._source." + COMPLETED_AT_FIELD_NAME + " == null) {" +              // already done
                "        if (ctx._source." + LEASE_HOLDER_ID_FIELD_NAME + " == params.workerId && " +
                "            ctx._source." + EXPIRATION_FIELD_NAME + " > serverTimeSeconds) {" + // count as an update to force the caller to lookup the expiration time, but no need to modify it
                "          ctx.op = \\\"update\\\";" +
                "        } else if (ctx._source." + EXPIRATION_FIELD_NAME + " < serverTimeSeconds && " + // is expired
                "                   ctx._source." + EXPIRATION_FIELD_NAME + " < newExpiration) {" +      // sanity check
                "          ctx._source." + EXPIRATION_FIELD_NAME + " = newExpiration;" +
                "          ctx._source." + LEASE_HOLDER_ID_FIELD_NAME + " = params.workerId;" +
                "          ctx._source.numAttempts += 1;" +
                "        } else {" +
                "          ctx.op = \\\"noop\\\";" +
                "        }" +
                "      } else if (params.expirationWindow != 0) {" +
                "        ctx.op = \\\"noop\\\";" +
                "      }" +
                "\"\n" +
                "  }\n" + // close script
                "}"; // close top-level

        var body = upsertLeaseBodyTemplate
                .replace(SCRIPT_VERSION_TEMPLATE, "poc")
                .replace(WORKER_ID_TEMPLATE, workerId)
                .replace(CLIENT_TIMESTAMP_TEMPLATE, Long.toString(clock.instant().toEpochMilli() / 1000))
                .replace(EXPIRATION_WINDOW_TEMPLATE, Long.toString(expirationWindowSeconds))
                .replace(CLOCK_DEVIATION_SECONDS_THRESHOLD_TEMPLATE, Long.toString(tolerableClientServerClockDifferenceSeconds));

        return httpClient.makeJsonRequest(POST_METHOD, INDEX_NAME + "/_update/" + workItemId,
                null, body);
    }

    DocumentModificationResult getResult(AbstractedHttpClient.AbstractHttpResponse response) throws IOException {
        if (response.getStatusCode() == 409) {
            return DocumentModificationResult.IGNORED;
        }
        final var resultDoc = objectMapper.readTree(response.getPayloadStream());
        var resultStr = resultDoc.path(RESULT_OPENSSEARCH_FIELD_NAME).textValue();
        return DocumentModificationResult.parse(resultStr);
    }

    @Override
    public boolean createUnassignedWorkItem(String workItemId) throws IOException {
        var response = createOrUpdateLeaseForDocument(workItemId, 0);
        try {
            return getResult(response) == DocumentModificationResult.CREATED;
        } catch (IllegalArgumentException e) {
            log.error("Error parsing resposne: " + response);
            throw e;
        }
    }

    @Override
    @NonNull
    public WorkAcquisitionOutcome createOrUpdateLeaseForWorkItem(String workItemId, Duration leaseDuration)
            throws IOException {
        var startTime = Instant.now();
        var updateResponse = createOrUpdateLeaseForDocument(workItemId, leaseDuration.toSeconds());
        var resultFromUpdate = getResult(updateResponse);

        if (resultFromUpdate == DocumentModificationResult.CREATED) {
            return new WorkItemAndDuration(workItemId, startTime.plus(leaseDuration));
        } else {
            final var httpResponse = httpClient.makeJsonRequest(GET_METHOD, INDEX_NAME + "/_doc/" + workItemId,
                    null, null);
            final var responseDoc = objectMapper.readTree(httpResponse.getPayloadStream()).path(SOURCE_FIELD_NAME);
            if (resultFromUpdate == DocumentModificationResult.UPDATED) {
                return new WorkItemAndDuration(workItemId, Instant.ofEpochMilli(1000 *
                        responseDoc.path(EXPIRATION_FIELD_NAME).longValue()));
            } else if (!responseDoc.path(COMPLETED_AT_FIELD_NAME).isMissingNode()) {
                return new AlreadyCompleted();
            } else if (resultFromUpdate == DocumentModificationResult.IGNORED) {
                throw new LeaseLockHeldElsewhereException();
            } else {
               throw new IllegalStateException("Unknown result: " + resultFromUpdate);
            }
        }
    }

    public void completeWorkItem(String workItemId) throws IOException {
        final var markWorkAsCompleteBodyTemplate = "{\n" +
                "  \"script\": {\n" +
                "    \"lang\": \"painless\",\n" +
                "    \"params\": { \n" +
                "      \"clientTimestamp\": " + CLIENT_TIMESTAMP_TEMPLATE + ",\n" +
                "      \"workerId\": \"" + WORKER_ID_TEMPLATE + "\"\n" +
                "    },\n" +
                "    \"source\": \"" +
                "      if (ctx._source.scriptVersion != \\\"" + SCRIPT_VERSION_TEMPLATE + "\\\") {" +
                "        throw new IllegalArgumentException(\\\"scriptVersion mismatch.  Not all participants are using the same script: sourceVersion=\\\" + ctx.source.scriptVersion);" +
                "      } " +
                "      if (ctx._source." + LEASE_HOLDER_ID_FIELD_NAME + " != params.workerId) {" +
                "        throw new IllegalArgumentException(\\\"work item was owned by \\\" + ctx._source." + LEASE_HOLDER_ID_FIELD_NAME + " + \\\" not \\\" + params.workerId);" +
                "      } else {" +
                "        ctx._source." + COMPLETED_AT_FIELD_NAME + " = System.currentTimeMillis() / 1000;" +
                "     }" +
                "\"\n" +
                "  }\n" +
                "}";

        var body = markWorkAsCompleteBodyTemplate
                .replace(SCRIPT_VERSION_TEMPLATE, "poc")
                .replace(WORKER_ID_TEMPLATE, workerId)
                .replace(CLIENT_TIMESTAMP_TEMPLATE, Long.toString(clock.instant().toEpochMilli()/1000));

        var response = httpClient.makeJsonRequest(POST_METHOD, INDEX_NAME + "/_update/" + workItemId,
                null, body);
        final var resultStr = objectMapper.readTree(response.getPayloadStream()).get(RESULT_OPENSSEARCH_FIELD_NAME).textValue();
        if (DocumentModificationResult.UPDATED != DocumentModificationResult.parse(resultStr)) {
            throw new IllegalStateException("Unexpected response for workItemId: " + workItemId + ".  Response: " +
                response.toDiagnosticString());
        }
    }

    private int numWorkItemsArePending(int maxItemsToCheckFor) throws IOException, InterruptedException {
        refresh();
        // TODO: Switch this to use _count
        log.warn("Switch this to use _count");
        final var queryBody = "{\n" +
                "\"query\": {" +
                "  \"bool\": {" +
                "    \"must\": [" +
                "      { \"exists\":" +
                "        { \"field\": \"" + EXPIRATION_FIELD_NAME + "\"}" +
                "      }" +
                "    ]," +
                "    \"must_not\": [" +
                "      { \"exists\":" +
                "        { \"field\": \"" + COMPLETED_AT_FIELD_NAME + "\"}" +
                "      }" +
                "    ]" +
                "  }" +
                "}" +
                "}";

        var path = INDEX_NAME + "/_search" + (maxItemsToCheckFor <= 0 ? "" : "?size=" + maxItemsToCheckFor);
        var response = httpClient.makeJsonRequest(POST_METHOD,  path, null, queryBody);

        final var resultHitsUpper = objectMapper.readTree(response.getPayloadStream()).path("hits");
        var statusCode = response.getStatusCode();
        if (statusCode != 200) {
            throw new IllegalStateException("Querying for pending (expired or not) work, " +
                    "returned an unexpected status code " + statusCode + " instead of 200");
        }
        return resultHitsUpper.path("hits").size();
    }

    @Override
    public int numWorkItemsArePending() throws IOException, InterruptedException {
        return numWorkItemsArePending(-1);
    }

    @Override
    public boolean workItemsArePending() throws IOException, InterruptedException {
        return numWorkItemsArePending(1) >= 1;
    }

    enum UpdateResult {
        SUCCESSFUL_ACQUISITION,
        VERSION_CONFLICT,
        NOTHING_TO_AQUIRE
    }

    /**
     * @param expirationWindowSeconds
     * @return true if a work item entry was assigned w/ a lease and false otherwise
     * @throws IOException if the request couldn't be made
     */
    UpdateResult assignOneWorkItem(long expirationWindowSeconds) throws IOException {
        // the random_score reduces the number of version conflicts from ~1200 for 40 concurrent requests
        // to acquire 40 units of work to around 800
        final var queryUpdateTemplate = "{\n" +
                "\"query\": {" +
                "  \"function_score\": {\n" +
                "    \"query\": {\n" +
                "      \"bool\": {" +
                "        \"must\": [" +
                "          {" +
                "            \"range\": {" +
                "              \"" + EXPIRATION_FIELD_NAME + "\": { \"lt\": " + OLD_EXPIRATION_THRESHOLD_TEMPLATE + " }" +
                "            }" +
                "          }" +
                "        ]," +
                "        \"must_not\": [" +
                "          { \"exists\":" +
                "            { \"field\": \"" + COMPLETED_AT_FIELD_NAME + "\"}" +
                "          }" +
                "        ]" +
                "      }" +
                "    }," +
                "    \"random_score\": {},\n" +
                "    \"boost_mode\": \"replace\"\n" + // Try to avoid the workers fighting for the same work items
                "  }" +
                "}," +
                "\"size\": 1,\n" +
                "\"script\": {" +
                "  \"params\": { \n" +
                "    \"clientTimestamp\": " + CLIENT_TIMESTAMP_TEMPLATE + ",\n" +
                "    \"expirationWindow\": " + EXPIRATION_WINDOW_TEMPLATE + ",\n" +
                "    \"workerId\": \"" + WORKER_ID_TEMPLATE + "\",\n" +
                "    \"counter\": 0\n" +
                "  },\n" +
                "  \"source\": \"" +
                "      if (ctx._source.scriptVersion != \\\"" + SCRIPT_VERSION_TEMPLATE + "\\\") {" +
                "        throw new IllegalArgumentException(\\\"scriptVersion mismatch.  Not all participants are using the same script: sourceVersion=\\\" + ctx.source.scriptVersion);" +
                "      } " +
                "      long serverTimeSeconds = System.currentTimeMillis() / 1000;" +
                "      if (Math.abs(params.clientTimestamp - serverTimeSeconds) > {CLOCK_DEVIATION_SECONDS_THRESHOLD}) {" +
                "        throw new IllegalArgumentException(\\\"The current times indicated between the client and server are too different.\\\");" +
                "      }" +
                "      long newExpiration = params.clientTimestamp + (((long)Math.pow(2, ctx._source.numAttempts)) * params.expirationWindow);" +
                "      if (ctx._source." + EXPIRATION_FIELD_NAME + " < serverTimeSeconds && " + // is expired
                "          ctx._source." + EXPIRATION_FIELD_NAME + " < newExpiration) {" +      // sanity check
                "        ctx._source." + EXPIRATION_FIELD_NAME + " = newExpiration;" +
                "        ctx._source." + LEASE_HOLDER_ID_FIELD_NAME + " = params.workerId;" +
                "        ctx._source.numAttempts += 1;" +
                "      }" +
                "\" " +  // end of source script contents
                "}" +    // end of script block
                "}";

        final var timestampEpochSeconds = clock.instant().toEpochMilli()/1000;
        final var body = queryUpdateTemplate
                .replace(SCRIPT_VERSION_TEMPLATE, "poc")
                .replace(WORKER_ID_TEMPLATE, workerId)
                .replace(CLIENT_TIMESTAMP_TEMPLATE, Long.toString(timestampEpochSeconds))
                .replace(OLD_EXPIRATION_THRESHOLD_TEMPLATE, Long.toString(timestampEpochSeconds+expirationWindowSeconds))
                .replace(EXPIRATION_WINDOW_TEMPLATE, Long.toString(expirationWindowSeconds))
                .replace(CLOCK_DEVIATION_SECONDS_THRESHOLD_TEMPLATE, Long.toString(tolerableClientServerClockDifferenceSeconds));

        var response = httpClient.makeJsonRequest(POST_METHOD,  INDEX_NAME + "/_update_by_query?refresh=true&max_docs=1",
                null, body);
        var resultTree = objectMapper.readTree(response.getPayloadStream());
        final var numUpdated = resultTree.path(UPDATED_COUNT_FIELD_NAME).longValue();
        assert numUpdated <= 1;
        if (numUpdated > 0) {
            return UpdateResult.SUCCESSFUL_ACQUISITION;
        } else if (resultTree.path(VERSION_CONFLICTS_FIELD_NAME).longValue() > 0) {
            return UpdateResult.VERSION_CONFLICT;
        } else if (resultTree.path("total").longValue() == 0) {
            return UpdateResult.NOTHING_TO_AQUIRE;
        } else {
            throw new IllegalStateException("Unexpected response for update: " + resultTree);
        }
    }

    private WorkItemAndDuration getAssignedWorkItem() throws IOException {
        final var queryWorkersAssignedItemsTemplate = "{\n" +
                "  \"query\": {\n" +
                "    \"bool\": {" +
                "      \"must\": [" +
                "        {" +
                "          \"term\": { \"" + LEASE_HOLDER_ID_FIELD_NAME + "\": \"" + WORKER_ID_TEMPLATE + "\"}\n" +
                "        }" +
                "      ]," +
                "      \"must_not\": [" +
                "        {" +
                "          \"exists\": { \"field\": \"" + COMPLETED_AT_FIELD_NAME + "\"}\n" +
                "        }" +
                "      ]" +
                "    }" +
                "  }" +
                "}";
        final var body = queryWorkersAssignedItemsTemplate.replace(WORKER_ID_TEMPLATE, workerId);
        var response = httpClient.makeJsonRequest(POST_METHOD,  INDEX_NAME + "/_search",
                null, body);

        final var resultHitsUpper = objectMapper.readTree(response.getPayloadStream()).path("hits");
        if (resultHitsUpper.isMissingNode()) {
            log.warn("Couldn't find the top level 'hits' field, returning null");
            return null;
        }
        final var numDocs = resultHitsUpper.path("total").path("value").longValue();
        if (numDocs != 1) {
            throw new IllegalStateException("The query for the assigned work document returned " + numDocs +
                    " instead of one item");
        }
        var resultHitInner = resultHitsUpper.path("hits").path(0);
        var expiration = resultHitInner.path(SOURCE_FIELD_NAME).path(EXPIRATION_FIELD_NAME).longValue();
        if (expiration == 0) {
            log.warn("Expiration wasn't found or wasn't set to > 0.  Returning null.");
            return null;
        }
        return new WorkItemAndDuration(resultHitInner.get("_id").asText(), Instant.ofEpochMilli(1000*expiration));
    }

    @AllArgsConstructor
    private static class MaxTriesExceededException extends Exception {
        Object suppliedValue;
        Object transformedValue;
    }

    private <T,U> U doUntil(String labelThatShouldBeAContext, long initialRetryDelayMs, int maxTries,
                            Supplier<T> supplier, Function<T,U> transformer, BiPredicate<T,U> test)
            throws InterruptedException, MaxTriesExceededException
    {
        var sleepMillis = initialRetryDelayMs;
        for (var attempt = 1; ; ++attempt) {
            var suppliedVal = supplier.get();
            var transformedVal = transformer.apply(suppliedVal);
            if (test.test(suppliedVal, transformedVal)) {
                return transformedVal;
            } else {
                log.atWarn().setMessage(() -> "Retrying " + labelThatShouldBeAContext +
                        " because the predicate failed for: (" + suppliedVal + "," + transformedVal + ")").log();
                if (attempt >= maxTries) {
                    throw new MaxTriesExceededException(suppliedVal, transformedVal);
                }
                Thread.sleep(sleepMillis);
                sleepMillis *= 2;
            }
        }
    }

    private void refresh() throws IOException, InterruptedException {
        try {
            doUntil("refresh", 100, MAX_RETRIES, () -> {
                        try {
                            return httpClient.makeJsonRequest(GET_METHOD, INDEX_NAME + "/_refresh",null,null);
                        } catch (IOException e) {
                            throw Lombok.sneakyThrow(e);
                        }
                    },
                    AbstractedHttpClient.AbstractHttpResponse::getStatusCode, (r, statusCode) -> statusCode == 200);
        } catch (MaxTriesExceededException e) {
            throw new IOException(e);
        }
    }

    public WorkAcquisitionOutcome acquireNextWorkItem(Duration leaseDuration) throws IOException, InterruptedException {
        refresh();
        while (true) {
            final var obtainResult = assignOneWorkItem(leaseDuration.toSeconds());
            switch (obtainResult) {
                case SUCCESSFUL_ACQUISITION:
                    return getAssignedWorkItem();
                case NOTHING_TO_AQUIRE:
                    return new NoAvailableWorkToBeDone();
                case VERSION_CONFLICT:
                    continue;
                default:
                    throw new IllegalStateException("unknown result from the assignOneWorkItem: " + obtainResult);
            }
        }
    }

}