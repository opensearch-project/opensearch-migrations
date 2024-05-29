package com.rfs.common;

import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.Optional;
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
     * Create a legacy template if it does not already exist.  Returns an Optional; if the template was created, it
     * will be the created object and empty otherwise.
     */
    public Optional<ObjectNode> createLegacyTemplate(String templateName, ObjectNode settings,
                                        IRfsContexts.ICheckedIdempotentPutRequestContext context) {
        String targetPath = "_template/" + templateName;
        return createObjectIdempotent(targetPath, settings, context);
    }

    /*
     * Create a component template if it does not already exist.  Returns an Optional; if the template was created, it
     * will be the created object and empty otherwise.
     */
    public Optional<ObjectNode> createComponentTemplate(String templateName, ObjectNode settings,
                                           IRfsContexts.ICheckedIdempotentPutRequestContext context) {
        String targetPath = "_component_template/" + templateName;
        return createObjectIdempotent(targetPath, settings, context);
    }

    /*
     * Create an index template if it does not already exist.  Returns an Optional; if the template was created, it
     * will be the created object and empty otherwise.
     */
    public Optional<ObjectNode> createIndexTemplate(String templateName, ObjectNode settings,
                                       IRfsContexts.ICheckedIdempotentPutRequestContext context) {
        String targetPath = "_index_template/" + templateName;
        return createObjectIdempotent(targetPath, settings, context);
    }

    /*
     * Create an index if it does not already exist.  Returns an Optional; if the index was created, it
     * will be the created object and empty otherwise.
     */
    public Optional<ObjectNode> createIndex(String indexName, ObjectNode settings,
                               IRfsContexts.ICheckedIdempotentPutRequestContext context) {
        String targetPath = indexName;
        return createObjectIdempotent(targetPath, settings, context);
    }

    private Optional<ObjectNode> createObjectIdempotent(String objectPath, ObjectNode settings,
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
            return Optional.of(settings);
        } else if (response.code == HttpURLConnection.HTTP_OK) {
            logger.info(objectPath + " already exists. Skipping creation.");
        } else {
            logger.warn("Could not confirm that " + objectPath + " does not already exist. Skipping creation.");
        }
        return Optional.empty();
    }

    /*
     * Attempts to register a snapshot repository; no-op if the repo already exists.  Returns an Optional; if the repo
     * was created, it will be the settings used and empty if it already existed.
     */
    public Optional<ObjectNode> registerSnapshotRepo(String repoName, ObjectNode settings,
                                                    IRfsContexts.ICreateSnapshotContext context) {
        String targetPath = "_snapshot/" + repoName;
        RestClient.Response response = client.putAsync(targetPath, settings.toString(), context.createRegisterRequest())
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

        if (response.code == HttpURLConnection.HTTP_CREATED) {
            return Optional.of(settings);
        } else {
            logger.info("Snapshot repo already exists. Registration is a no-op.");
            return Optional.empty();
        }
    }

    /*
     * Attempts to create a snapshot; no-op if the snapshot already exists.  Returns an Optional; if the snapshot
     * was created, it will be the settings used and empty if it already existed.
     */
    public Optional<ObjectNode> createSnapshot(String repoName, String snapshotName, ObjectNode settings,
                                               IRfsContexts.ICreateSnapshotContext context) {
        String targetPath = "_snapshot/" + repoName + "/" + snapshotName;
        RestClient.Response response = client.putAsync(targetPath, settings.toString(), context.createSnapshotContext())
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


        if (response.code == HttpURLConnection.HTTP_CREATED) {
            return Optional.of(settings);
        } else {
            logger.info("Snapshot already exists. Creation is a no-op.");
            return Optional.empty();
        }
    }

    /*
     * Get the status of a snapshot.  Returns an Optional; if the snapshot was found, it will be the snapshot status
     * and empty otherwise.
     */
    public Optional<ObjectNode> getSnapshotStatus(String repoName, String snapshotName,
                                                  IRfsContexts.ICreateSnapshotContext context) {
        String targetPath = "_snapshot/" + repoName + "/" + snapshotName;
        RestClient.Response response = client.getAsync(targetPath, context.createGetSnapshotContext())
            .flatMap(resp -> {
                if (resp.code == HttpURLConnection.HTTP_OK || resp.code == HttpURLConnection.HTTP_NOT_FOUND) {
                    return Mono.just(resp);
                } else {
                    String errorMessage = "Could get status of snapshot: " + targetPath + ". Response Code: " + resp.code + ", Response Body: " + resp.body;
                    return Mono.error(new OperationFailed(errorMessage, resp));
                }
            })
            .doOnError(e -> logger.error(e.getMessage()))
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10)))
            .block();

        if (response.code == HttpURLConnection.HTTP_OK) {
            try {
                return Optional.of(objectMapper.readValue(response.body, ObjectNode.class));
            } catch (Exception e) {
                String errorMessage = "Could not parse response for: _snapshot/" + repoName + "/" + snapshotName;
                throw new OperationFailed(errorMessage, response);
            }
        } else if (response.code == HttpURLConnection.HTTP_NOT_FOUND) {
            return Optional.empty();
        } else {
            String errorMessage = "Should not have gotten here while parsing response for: _snapshot/" + repoName + "/" + snapshotName;
            throw new OperationFailed(errorMessage, response);
        }
    }

    /*
     * Create a document if it does not already exist.  Returns an Optional; if the document was created, it
     * will be the created object and empty otherwise.
     */
    public Optional<ObjectNode> createDocument(String indexName, String documentId, ObjectNode body,
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
            return Optional.of(body);
        } else {
            // The only response code that can end up here is HTTP_CONFLICT, as everything is an error above
            // This indicates that the document already exists
            return Optional.empty();
        }
    }

    /*
     * Retrieve a document.  Returns an Optional; if the document was found, it will be the document and empty otherwise.
     */
    public Optional<ObjectNode> getDocument(String indexName, String documentId,
                                           IRfsContexts.IRequestContext context) {
        String targetPath = indexName + "/_doc/" + documentId;
        RestClient.Response response = client.getAsync(targetPath, context)
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

        if (response.code == HttpURLConnection.HTTP_OK) {
            try {
                return Optional.of(objectMapper.readValue(response.body, ObjectNode.class));
            } catch (Exception e) {
                String errorMessage = "Could not parse response for: " + indexName + "/" + documentId;
                throw new OperationFailed(errorMessage, response);
            }
        } else if (response.code == HttpURLConnection.HTTP_NOT_FOUND) {
            return Optional.empty();
        } else {
            String errorMessage = "Should not have gotten here while parsing response for: " + indexName + "/" + documentId;
            throw new OperationFailed(errorMessage, response);
        }
    }

    /*
     * Update a document using optimistic locking.  Returns an Optional; if the document was updated, it
     * will be the new value and empty otherwise.
     */
    public Optional<ObjectNode> updateDocument(String indexName, String documentId, ObjectNode body,
                                  IRfsContexts.IRequestContext context) {
        Optional<ObjectNode> document = getDocument(indexName, documentId, context);
        if (document.isEmpty()) {
            throw new UpdateFailed("Document not found: " + indexName + "/" + documentId);
        }

        String currentSeqNum;
        String currentPrimaryTerm;
        try {
            currentSeqNum = document.get().get("_seq_no").asText();
            currentPrimaryTerm = document.get().get("_primary_term").asText();
        } catch (Exception e) {
            String errorMessage = "Could not update document: " + indexName + "/" + documentId;
            throw new RfsException(errorMessage, e);
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
            return Optional.of(body);
        } else {
            // The only response code that can end up here is HTTP_CONFLICT, as everything is an error above
            // This indicates that we didn't acquire the optimistic lock
            return Optional.empty();
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

    public static class UpdateFailed extends RfsException {
        public UpdateFailed(String message) {
            super(message);
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
