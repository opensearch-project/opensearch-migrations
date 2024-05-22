package com.rfs.common;

import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.rfs.tracing.IRfsContexts;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

public class OpenSearchClient {
    private static final Logger logger = LogManager.getLogger(OpenSearchClient.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public final ConnectionDetails connectionDetails;
    private final RestClient client;

    public OpenSearchClient(ConnectionDetails connectionDetails) {
        this.connectionDetails = connectionDetails;
        this.client = new RestClient(connectionDetails);
    }

    /*
     * Idempotently create a legacy template if it does not already exist; return true if created, false otherwise.
     */
    public boolean createLegacyTemplate(String templateName, ObjectNode settings,
                                        IRfsContexts.ICheckedIdempotentPutRequestContext context) {
        String targetPath = "_template/" + templateName;
        return createObjectIdempotent(targetPath, settings, context);
    }

    /*
     * Idempotently create a component template if it does not already exist; return true if created, false otherwise.
     */
    public boolean createComponentTemplate(String templateName, ObjectNode settings,
                                           IRfsContexts.ICheckedIdempotentPutRequestContext context) {
        String targetPath = "_component_template/" + templateName;
        return createObjectIdempotent(targetPath, settings, context);
    }

    /*
     * Idempotently create an index template if it does not already exist; return true if created, false otherwise.
     */
    public boolean createIndexTemplate(String templateName, ObjectNode settings,
                                       IRfsContexts.ICheckedIdempotentPutRequestContext context) {
        String targetPath = "_index_template/" + templateName;
        return createObjectIdempotent(targetPath, settings, context);
    }

    /*
     * Idempotently create an index if it does not already exist; return true if created, false otherwise.
     */
    public boolean createIndex(String indexName, ObjectNode settings,
                               IRfsContexts.ICheckedIdempotentPutRequestContext context) {
        String targetPath = indexName;
        return createObjectIdempotent(targetPath, settings, context);
    }

    private boolean createObjectIdempotent(String objectPath, ObjectNode settings,
                                           IRfsContexts.ICheckedIdempotentPutRequestContext context) {
        RestClient.Response response = client.getAsync(objectPath, context.createCheckRequestContext())
        .flatMap(resp -> {
            if (resp.code == HttpURLConnection.HTTP_NOT_FOUND || resp.code == HttpURLConnection.HTTP_OK) {
                return Mono.just(resp);
            } else {
                String errorMessage = ("Could not create object: " + objectPath + ". Response Code: " + resp.code
                    + ", Response Message: " + resp.message + ", Response Body: " + resp.body);
                return Mono.error(new OperationFailed(errorMessage, resp));
            }
        })
        .doOnError(e -> logger.error(e.getMessage()))
        .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10)))
        .block();

        if (response.code == HttpURLConnection.HTTP_NOT_FOUND) {
            client.put(objectPath, settings.toString(), context.createCheckRequestContext());
            return true;
        } else if (response.code == HttpURLConnection.HTTP_OK) {
            logger.info(objectPath + " already exists. Skipping creation.");
        } else {
            logger.warn("Could not confirm that " + objectPath + " does not already exist. Skipping creation.");
        }
        return false;
    }

    public RestClient.Response registerSnapshotRepo(String repoName, ObjectNode settings,
                                                    IRfsContexts.ICreateSnapshotContext context) {
        String targetPath = "_snapshot/" + repoName;
        return client.putAsync(targetPath, settings.toString(), context.createRegisterRequest())
            .flatMap(resp -> {
                if (resp.code == HttpURLConnection.HTTP_OK || resp.code == HttpURLConnection.HTTP_CREATED) {
                    return Mono.just(resp);
                } else {
                    String errorMessage = ("Could not register snapshot repo: " + targetPath + ". Response Code: " + resp.code
                        + ", Response Message: " + resp.message + ", Response Body: " + resp.body);
                    return Mono.error(new OperationFailed(errorMessage, resp));
                }
            })
            .doOnError(e -> logger.error(e.getMessage()))
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10)))
            .block();
    }

    public RestClient.Response createSnapshot(String repoName, String snapshotName, ObjectNode settings,
                                              IRfsContexts.ICreateSnapshotContext context) {
        String targetPath = "_snapshot/" + repoName + "/" + snapshotName;
        return client.putAsync(targetPath, settings.toString(), context.createSnapshotContext())
            .flatMap(resp -> {
                if (resp.code == HttpURLConnection.HTTP_OK || resp.code == HttpURLConnection.HTTP_CREATED) {
                    return Mono.just(resp);
                } else {
                    String errorMessage = ("Could not create snapshot: " + targetPath + ". Response Code: " + resp.code
                        + ", Response Message: " + resp.message + ", Response Body: " + resp.body);
                    return Mono.error(new OperationFailed(errorMessage, resp));
                }
            })
            .doOnError(e -> logger.error(e.getMessage()))
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10)))
            .block();
    }

    public RestClient.Response getSnapshotStatus(String repoName, String snapshotName,
                                                 IRfsContexts.ICreateSnapshotContext context) {
        String targetPath = "_snapshot/" + repoName + "/" + snapshotName;
        return client.getAsync(targetPath, context.createGetSnapshotContext())
            .flatMap(resp -> {
                if (resp.code == HttpURLConnection.HTTP_OK) {
                    return Mono.just(resp);
                } else {
                    String errorMessage = "Could get status of snapshot: " + targetPath + ". Response Code: " + resp.code + ", Response Body: " + resp.body;
                    return Mono.error(new OperationFailed(errorMessage, resp));
                }
            })
            .doOnError(e -> logger.error(e.getMessage()))
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10)))
            .block();
    }

    /*
     * Idempotently create a document if it does not already exist; return true if created, false otherwise.
     */
    public boolean createDocument(String indexName, String documentId, ObjectNode body,
                                  IRfsContexts.IRequestContext context) {
        String targetPath = indexName + "/_doc/" + documentId + "?op_type=create";
        RestClient.Response response = client.putAsync(targetPath, body.toString(), context)
            .flatMap(resp -> {
                if (resp.code == HttpURLConnection.HTTP_CREATED || resp.code == HttpURLConnection.HTTP_CONFLICT) {
                    return Mono.just(resp);
                } else {
                    String errorMessage = ("Could not create document: " + indexName + "/" + documentId + ". Response Code: " + resp.code
                        + ", Response Message: " + resp.message + ", Response Body: " + resp.body);
                    return Mono.error(new OperationFailed(errorMessage, resp));
                }
            })
            .doOnError(e -> logger.error(e.getMessage()))
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10)))
            .block();
        if (response.code == HttpURLConnection.HTTP_CREATED) {
            return true;
        } else {
            // The only response code that can end up here is HTTP_CONFLICT, as everything is an error above
            return false;
        }
    }

    public RestClient.Response getDocument(String indexName, String documentId,
                                           IRfsContexts.IRequestContext context) {
        String targetPath = indexName + "/_doc/" + documentId;
        return client.getAsync(targetPath, context)
            .flatMap(resp -> {
                if (resp.code == HttpURLConnection.HTTP_OK || resp.code == HttpURLConnection.HTTP_NOT_FOUND) {
                    return Mono.just(resp);
                } else {
                    String errorMessage = ("Could not retrieve document: " + indexName + "/" + documentId + ". Response Code: " + resp.code
                        + ", Response Message: " + resp.message + ", Response Body: " + resp.body);
                    return Mono.error(new OperationFailed(errorMessage, resp));
                }
            })
            .doOnError(e -> logger.error(e.getMessage()))
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10)))
            .block();
    }

    /*
     * Update a document using optimistic locking; return true if updated, false otherwise.
     */
    public boolean updateDocument(String indexName, String documentId, ObjectNode body,
                                  IRfsContexts.IRequestContext context) {
        RestClient.Response getResponse = getDocument(indexName, documentId, context);

        String currentSeqNum;
        String currentPrimaryTerm;
        try {
            ObjectNode document = (ObjectNode) objectMapper.readTree(getResponse.body);
            currentSeqNum = document.get("_seq_no").asText();
            currentPrimaryTerm = document.get("_primary_term").asText();
        } catch (Exception e) {
            String errorMessage = "Could not update document: " + indexName + "/" + documentId + ". Response Code: " + getResponse.code;
            throw new OperationFailed(errorMessage, getResponse);
        }

        ObjectNode upsertBody = new ObjectMapper().createObjectNode();
        upsertBody.set("doc", body);

        String targetPath = indexName + "/_update/" + documentId + "?if_seq_no=" + currentSeqNum + "&if_primary_term=" + currentPrimaryTerm;
        RestClient.Response response = client.postAsync(targetPath, upsertBody.toString(), context)
            .flatMap(resp -> {
                if (resp.code == HttpURLConnection.HTTP_OK || resp.code == HttpURLConnection.HTTP_CONFLICT) {
                    return Mono.just(resp);
                } else {

                    String errorMessage = ("Could not update document: " + indexName + "/" + documentId + ". Response Code: " + resp.code
                        + ", Response Message: " + resp.message + ", Response Body: " + resp.body);
                    return Mono.error(new OperationFailed(errorMessage, resp));
                }
            })
            .doOnError(e -> logger.error(e.getMessage()))
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10)))
            .block();
        if (response.code == HttpURLConnection.HTTP_OK) {
            return true;
        } else {
            // The only response code that can end up here is HTTP_CONFLICT, as everything is an error above
            return false;
        }
    }

    public Mono<BulkResponse> sendBulkRequest(String indexName, String body,
                                              IRfsContexts.IRequestContext context) {
        String targetPath = indexName + "/_bulk";

        return client.postAsync(targetPath, body, context)
            .map(response -> new BulkResponse(response.code, response.body, response.message))
            .flatMap(resp -> {
                if (resp.hasBadStatusCode() || resp.hasFailedOperations()) {
                    logger.error(resp.getFailureMessage());
                    return Mono.error(new OperationFailed(resp.getFailureMessage(), resp));
                }
                return Mono.just(resp);
            })
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10)));
    }

    public RestClient.Response refresh(IRfsContexts.IRequestContext context) {
        String targetPath = "_refresh";
        return client.get(targetPath, context);
    }

    public static class BulkResponse extends RestClient.Response {
        public BulkResponse(int responseCode, String responseBody, String responseMessage) {
            super(responseCode, responseBody, responseMessage);
        }

        public boolean hasBadStatusCode() {
            return !(code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_CREATED);
        }

        public boolean hasFailedOperations() {
            // The OpenSearch Bulk API response body is JSON and contains a top-level "errors" field that indicates
            // whether any of the individual operations in the bulk request failed.  Rather than marshalling the entire
            // response as JSON, just check for the string value.

            String regexPattern = "\"errors\"\\s*:\\s*true";
            Pattern pattern = Pattern.compile(regexPattern);
            Matcher matcher = pattern.matcher(body);
            return matcher.find();
        }

        public String getFailureMessage() {
            String failureMessage;
            if (hasBadStatusCode()) {
                failureMessage = "Bulk request failed.  Status code: " + code + ", Response body: " + body;
            } else {
                failureMessage = "Bulk request succeeded, but some operations failed.  Response body: " + body;
            }

            return failureMessage;
        }
    }

    public static class OperationFailed extends RfsException {
        public final RestClient.Response response;

        public OperationFailed(String message, RestClient.Response response) {
            super(message);

            this.response = response;
        }
    }
}
