package com.rfs.cms;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.classic.methods.HttpOptions;
import org.apache.hc.client5.http.classic.methods.HttpPatch;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class WorkCoordinator implements IWorkCoordinator {
    private static final String INDEX_NAME = ".migrations_working_state";

    public static final String SCRIPT_VERSION_TEMPLATE = "{SCRIPT_VERSION}";
    public static final String WORKER_ID_TEMPLATE = "{WORKER_ID}";
    public static final String CLIENT_TIMESTAMP_TEMPLATE = "{CLIENT_TIMESTAMP}";
    public static final String EXPIRATION_WINDOW_TEMPLATE = "{EXPIRATION_WINDOW}";
    public static final String CLOCK_DEVIATION_SECONDS_THRESHOLD_TEMPLATE = "{CLOCK_DEVIATION_SECONDS_THRESHOLD}";

    public static final String PUT_METHOD = "PUT";
    public static final String POST_METHOD = "POST";
    public static final String RESULT_OPENSSEARCH_KEYWORD = "result";

    private final long tolerableClientServerClockDifference;
    private final AbstractedHttpClient httpClient;
    private final String workerId;
    private final ObjectMapper objectMapper;

    public WorkCoordinator(URI openSearchUri, long tolerableClientServerClockDifference, String workerId) {
        this.tolerableClientServerClockDifference = tolerableClientServerClockDifference;
        this.httpClient = new ApacheHttpClient(openSearchUri);
        this.workerId = workerId;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void close() throws Exception {
        httpClient.close();
    }

    public interface AbstractHttpResponse {
        Stream<Map.Entry<String, String>> getHeaders();
        default byte[] getPayloadBytes() throws IOException {
            return getPayloadStream().readAllBytes();
        }
        default InputStream getPayloadStream() throws IOException {
            return new ByteArrayInputStream(getPayloadBytes());
        }
        String getStatusText();
        int getStatusCode();

        default String toDiagnosticString() {
            String payloadStr;
            try {
                payloadStr = Arrays.toString(getPayloadBytes());
            } catch (IOException e) {
                payloadStr = "[EXCEPTION EVALUATING PAYLOAD]: " + e;
            }
            return getStatusText() + "/" + getStatusCode() +
                    getHeaders().map(kvp->kvp.getKey() + ": " + kvp.getValue())
                            .collect(Collectors.joining(";", "[","]")) +
                    payloadStr;
        }
    }

    public interface AbstractedHttpClient extends AutoCloseable {
        AbstractHttpResponse makeRequest(String method, String path,
                                         Map<String, String> headers, String payload) throws IOException;

        default AbstractHttpResponse makeJsonRequest(String method, String path,
                                                     Map<String, String> extraHeaders, String body) throws IOException {
            var combinedHeaders = new LinkedHashMap<String, String>();
            combinedHeaders.put("Content-Type", "application/json");
            combinedHeaders.put("Accept-Encoding", "identity");
            if (extraHeaders != null) {
                combinedHeaders.putAll(extraHeaders);
            }
            return makeRequest(method, path, combinedHeaders, body);
        }
    }

    public static class ApacheHttpClient implements AbstractedHttpClient {
        private final CloseableHttpClient client = HttpClients.createDefault();
        private final URI baseUri;

        public ApacheHttpClient(URI baseUri) {
            this.baseUri = baseUri;
        }

        private static HttpUriRequestBase makeRequestBase(URI baseUri, String method, String path) {
            switch (method.toUpperCase()) {
                case "GET":
                    return new HttpGet(baseUri + "/" + INDEX_NAME + path);
                case POST_METHOD:
                    return new HttpPost(baseUri + "/" + INDEX_NAME + path);
                case PUT_METHOD:
                    return new HttpPut(baseUri + "/" + INDEX_NAME + path);
                case "PATCH":
                    return new HttpPatch(baseUri + "/" + INDEX_NAME + path);
                case "HEAD":
                    return new HttpHead(baseUri + "/" + INDEX_NAME + path);
                case "OPTIONS":
                    return new HttpOptions(baseUri + "/" + INDEX_NAME + path);
                case "DELETE":
                    return new HttpDelete(baseUri + "/" + INDEX_NAME + path);
                default:
                    throw new IllegalArgumentException("Cannot map method to an Apache Http Client request: " + method);
            }
        }

        @Override
        public AbstractHttpResponse makeRequest(String method, String path,
                                                Map<String, String> headers, String payload) throws IOException {
            var request = makeRequestBase(baseUri, method, path);
            request.setHeaders(request.getHeaders());
            request.setEntity(new StringEntity(payload));
            return client.execute(request, fr -> new AbstractHttpResponse() {
                @Override
                public InputStream getPayloadStream() throws IOException {
                    return fr.getEntity().getContent();
                }

                @Override
                public String getStatusText() {
                    return fr.getReasonPhrase();
                }

                @Override
                public int getStatusCode() {
                    return fr.getCode();
                }

                @Override
                public Stream<Map.Entry<String, String>> getHeaders() {
                    return Arrays.stream(fr.getHeaders())
                            .map(h -> new AbstractMap.SimpleEntry<>(h.getName(), h.getValue()));
                }
            });
        }

        @Override
        public void close() throws Exception {
            client.close();
        }
    }

    public void setup() throws IOException {
        var body = "{\n" +
                "  \"settings\": {\n" +
                "   \"index\": {" +
                "    \"number_of_shards\": 1,\n" +
                "    \"number_of_replicas\": 1\n" +
                "   }\n" +
                "  },\n" +
                "  \"mappings\": {\n" +
                "    \"properties\": {\n" +
                "      \"expiration\": {\n" +
                "        \"type\": \"long\"\n" +
                "      },\n" +
                "      \"completedAt\": {\n" +
                "        \"type\": \"long\"\n" +
                "      },\n" +
                "      \"workerId\": {\n" +
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

        var response = httpClient.makeJsonRequest(PUT_METHOD, "/" + INDEX_NAME, null, body);
        if (response.getStatusCode() != 200) {
            throw new IOException("Could not setup " + INDEX_NAME + ".  " +
                    "Got error code " + response.getStatusCode() + " and response: " +
                    Arrays.toString(response.getPayloadBytes()));
        }
    }

    enum DocumentResult {
        IGNORED, CREATED, UPDATED;
        static DocumentResult parse(String s) {
            switch (s) {
                case "noop": return DocumentResult.IGNORED;
                case "created": return DocumentResult.CREATED;
                case "updated": return DocumentResult.UPDATED;
                default:
                    throw new IllegalArgumentException("Unknown result " + s);
            }
        }
    }

    DocumentResult createOrUpdateLeaseForDocument(String workItemId, Instant currentTime, int expirationWindowSeconds,
                                                  Predicate<DocumentResult> returnValueAdapter)
            throws IOException {
        // the notion of 'now' isn't supported with painless scripts
        // https://www.elastic.co/guide/en/elasticsearch/painless/current/painless-datetime.html#_datetime_now
        final var upsertLeaseBodyTemplate = "{\n" +
                "  \"scripted_upsert\": true,\n" +
                "  \"upsert\": {\n" +
                "    \"scriptVersion\": \"" + SCRIPT_VERSION_TEMPLATE + "\",\n" +
                "    \"expiration\": 0,\n" +
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
                "          ctx._source.completedAt == null && " +             // not completed
                "          ctx._source.expiration < serverTimeSeconds && " +  // is expired
                "          ctx._source.expiration < newExpiration) {" +       // sanity check
                "        ctx._source.expiration = newExpiration;" +
                "        ctx._source.leaseHolderId = params.workerId;" +
                "        ctx._source.numAttempts += 1;" +
                "      } else if (params.expirationWindow != 0) {" +
                "        ctx.op = \\\"noop\\\";" +
                "      }" +
                "\"\n" +
                "  }\n" + // close script
                "}"; // close top-level

        var body = upsertLeaseBodyTemplate
                .replace(SCRIPT_VERSION_TEMPLATE, "poc")
                .replace(WORKER_ID_TEMPLATE, workerId)
                .replace(CLIENT_TIMESTAMP_TEMPLATE, Long.toString(currentTime.toEpochMilli()/1000))
                .replace(EXPIRATION_WINDOW_TEMPLATE, Integer.toString(expirationWindowSeconds))
                .replace(CLOCK_DEVIATION_SECONDS_THRESHOLD_TEMPLATE, Long.toString(tolerableClientServerClockDifference));

        var response = httpClient.makeJsonRequest(POST_METHOD, "/" + INDEX_NAME + "/" + workItemId,
                null, body);
        final var resultStr =
                objectMapper.readTree(response.getPayloadStream()).get(RESULT_OPENSSEARCH_KEYWORD).textValue();
        var rval = DocumentResult.parse(resultStr);
        if (!returnValueAdapter.test(rval)) {
            throw Lombok.sneakyThrow(
                    new IllegalStateException("Unexpected response for workItemId: " + workItemId + ".  Response: " +
                            response.toDiagnosticString()));
        }
        return rval;
    }

    public void createUnassignedWorkItem(String workItemId) throws IOException {
        createOrUpdateLeaseForDocument(workItemId, Instant.now(), 0,
                r -> r == DocumentResult.CREATED);
    }

    <T> T markWorkItemAsComplete(String workItemId, Instant currentTime,
                                 BiFunction<AbstractHttpResponse, DocumentResult, T> returnValueAdapter)
            throws IOException {
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
                "      if (ctx._source.leaseHolderId != params.workerId) {" +
                "        throw new IllegalArgumentException(\\\"work item was owned by \\\" + ctx._source.leaseHolderId + \\\" not \\\" + params.workerId);" +
                "      } else {" +
                "        ctx._source.completedAt = System.currentTimeMillis() / 1000;" +
                "     }" +
                "\"\n" +
                "  }\n" +
                "}";

        var body = markWorkAsCompleteBodyTemplate
                .replace(SCRIPT_VERSION_TEMPLATE, "poc")
                .replace(WORKER_ID_TEMPLATE, workerId)
                .replace(CLIENT_TIMESTAMP_TEMPLATE, Long.toString(currentTime.toEpochMilli()/1000));

        var response = httpClient.makeJsonRequest(POST_METHOD, "/" + INDEX_NAME + "/_update/" + workItemId,
                null, body);
        final var resultStr = objectMapper.readTree(response.getPayloadStream()).get(RESULT_OPENSSEARCH_KEYWORD).textValue();
        return returnValueAdapter.apply(response, DocumentResult.parse(resultStr));
    }

    <T> T makeQueryUpdateDocument(String workerId, Instant currentTime, int expirationWindowSeconds,
                                  BiFunction<AbstractHttpResponse, DocumentResult, T> returnValueAdapter)
            throws IOException {
        final var queryUpdateTemplate = "{\n" +
                "\"query\": {" +
                "  \"bool\": {" +
                "    \"must\": [" +
                "      {" +
                "        \"range\": {" +
                "          \"expiration\": { \"lt\": 1718247000 }" +
                "        }" +
                "      }" +
                "    ]," +
                "    \"must_not\": [" +
                "      { \"exists\":" +
                "        { \"field\": \"completedAt\"}" +
                "      }" +
                "    ]" +
                "  }" +
                "}," +
                "\"script\": {" +
                "  \"params\": { \n" +
                "    \"clientTimestamp\": " + CLIENT_TIMESTAMP_TEMPLATE + ",\n" +
                "    \"expirationWindow\": " + EXPIRATION_WINDOW_TEMPLATE + ",\n" +
                "    \"workerId\": \"" + WORKER_ID_TEMPLATE + "\"\n" +
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
                "      if (ctx._source.expiration < serverTimeSeconds && " + // is expired
                "          ctx._source.expiration < newExpiration) {" +      // sanity check
                "        ctx._source.expiration = newExpiration;" +
                "        ctx._source.leaseHolderId = params.workerId;" +
                "        ctx._source.numAttempts += 1;" +
                "      }" +
                "\" " +  // end of source script contents
                "}" +    // end of script block
                "}";

        final var body = queryUpdateTemplate
                .replace(SCRIPT_VERSION_TEMPLATE, "poc")
                .replace(WORKER_ID_TEMPLATE, workerId)
                .replace(CLIENT_TIMESTAMP_TEMPLATE, Long.toString(currentTime.toEpochMilli()/1000))
                .replace(EXPIRATION_WINDOW_TEMPLATE, Integer.toString(expirationWindowSeconds))
                .replace(CLOCK_DEVIATION_SECONDS_THRESHOLD_TEMPLATE, Long.toString(tolerableClientServerClockDifference));

        var response = httpClient.makeJsonRequest(POST_METHOD, "/" + INDEX_NAME + "/_update_by_query?refresh=true",
                null, body);
        final var resultStr = objectMapper.readTree(response.getPayloadStream()).get(RESULT_OPENSSEARCH_KEYWORD).textValue();
        return returnValueAdapter.apply(response, DocumentResult.parse(resultStr));
    }

    private String makeQueryAssignedWorkDocument(String workerId) throws IOException {
        final var queryWorkersAssignedItemsTemplate = "{\n" +
                "  \"query\": {\n" +
                "    \"term\": { \"leaseHolderId\": \"" + WORKER_ID_TEMPLATE + "\"}\n" +
                "  }\n" +
                "}";
        final var body = queryWorkersAssignedItemsTemplate.replace(WORKER_ID_TEMPLATE, workerId);
        var response = httpClient.makeJsonRequest(POST_METHOD, "/" + INDEX_NAME + "/_search",
                null, body);
        final var resultStr = objectMapper.readTree(response.getPayloadStream()).get(RESULT_OPENSSEARCH_KEYWORD).textValue();
        return returnValueAdapter.apply(response, DocumentResult.CREATED.parse(resultStr));
    }
}