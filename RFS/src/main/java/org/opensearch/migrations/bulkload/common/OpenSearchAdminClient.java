package org.opensearch.migrations.bulkload.common;

import java.net.HttpURLConnection;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import org.opensearch.migrations.AwarenessAttributeSettings;
import org.opensearch.migrations.Flavor;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import static org.opensearch.migrations.bulkload.common.OpenSearchClient.OperationFailed;

final class OpenSearchAdminClient {
    private static final Logger log = LoggerFactory.getLogger(OpenSearchClient.class);
    private static final ObjectMapper OBJECT_MAPPER = OpenSearchClient.OBJECT_MAPPER;
    private static final int DEFAULT_MAX_RETRY_ATTEMPTS = 3;
    private static final java.time.Duration DEFAULT_BACKOFF = java.time.Duration.ofSeconds(1);
    private static final java.time.Duration DEFAULT_MAX_BACKOFF = java.time.Duration.ofSeconds(10);
    private static final Retry SNAPSHOT_RETRY_STRATEGY =
        Retry.backoff(DEFAULT_MAX_RETRY_ATTEMPTS, DEFAULT_BACKOFF).maxBackoff(DEFAULT_MAX_BACKOFF);
    static final Retry CHECK_IF_ITEM_EXISTS_RETRY_STRATEGY =
        Retry.backoff(DEFAULT_MAX_RETRY_ATTEMPTS, DEFAULT_BACKOFF).maxBackoff(DEFAULT_MAX_BACKOFF);
    private static final Retry CREATE_ITEM_EXISTS_RETRY_STRATEGY =
        Retry.backoff(DEFAULT_MAX_RETRY_ATTEMPTS, DEFAULT_BACKOFF)
            .maxBackoff(DEFAULT_MAX_BACKOFF)
            .filter(throwable -> !(throwable instanceof InvalidResponse));
    static final String SNAPSHOT_PREFIX_STR = "_snapshot/";
    private static final String PATH_DELIMITER = "/";
    private static final String NULL_RESPONSE_ASSERTION =
        "getResponse should not be null; it should either be a valid response or an exception should have been thrown.";

    private final RestClient client;
    private final Version version;
    private final UnaryOperator<String> createIndexPath;

    OpenSearchAdminClient(RestClient client, Version version, UnaryOperator<String> createIndexPath) {
        this.client = client;
        this.version = version;
        this.createIndexPath = createIndexPath;
    }

    private JsonNode getSettingFromPersistentOrDefaults(String path, ObjectNode settings) {
        return settings.get("persistent").has(path)
            ? settings.get("persistent").get(path)
            : settings.get("defaults").get(path);
    }

    AwarenessAttributeSettings getAwarenessAttributeSettings() {
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
                if (resp.statusCode == HttpURLConnection.HTTP_OK) {
                    return Mono.just(resp);
                } else {
                    String errorMessage =
                        "Could not retrieve cluster settings: " + settingsPath + ". " + getString(resp);
                    return Mono.error(new OperationFailed(errorMessage, resp));
                }
            })
            .doOnError(e -> log.error(e.getMessage()))
            .retryWhen(CHECK_IF_ITEM_EXISTS_RETRY_STRATEGY)
            .block();
        long duration = System.currentTimeMillis() - startTime;
        log.info(
            "Completed getAwarenessAttributeSettings in {} ms with statusCode={}",
            duration,
            getResponse != null ? getResponse.statusCode : "null"
        );
        assert getResponse != null : NULL_RESPONSE_ASSERTION;
        ObjectNode settings;

        String balanceIsEnabledSetting = "cluster.routing.allocation.awareness.balance";

        try {
            settings = OBJECT_MAPPER.readValue(getResponse.body, ObjectNode.class);
        } catch (Exception e) {
            throw new OperationFailed("Could not parse settings values", getResponse);
        }
        boolean balanceIsEnabled =
            Optional.ofNullable(getSettingFromPersistentOrDefaults(balanceIsEnabledSetting, settings))
                .map(JsonNode::asBoolean)
                .orElse(false);

        if (!balanceIsEnabled) {
            return new AwarenessAttributeSettings(false, 0);
        }
        AtomicInteger attributeValues = new AtomicInteger(1);

        String balanceAttributeSetting = "cluster.routing.allocation.awareness.attributes";
        String balanceAttributeValues = "cluster.routing.allocation.awareness.force.";

        Optional.ofNullable(getSettingFromPersistentOrDefaults(balanceAttributeSetting, settings))
            .ifPresent(attributes -> {
                attributes.forEach(attributeName -> {
                    Optional.ofNullable(
                        getSettingFromPersistentOrDefaults(
                            balanceAttributeValues + attributeName.asText() + ".values",
                            settings
                        )
                    )
                        .map(JsonNode::asText)
                        .map(text -> text.split(","))
                        .ifPresent(values -> attributeValues.getAndAccumulate(values.length, Math::max));
                });
            });
        return new AwarenessAttributeSettings(true, attributeValues.get());
    }

    Optional<ObjectNode> createLegacyTemplate(
        String templateName,
        ObjectNode settings,
        IRfsContexts.ICheckedIdempotentPutRequestContext context
    ) {
        return createObjectIdempotent("_template/" + templateName, settings, context);
    }

    Optional<ObjectNode> createComponentTemplate(
        String templateName,
        ObjectNode settings,
        IRfsContexts.ICheckedIdempotentPutRequestContext context
    ) {
        return createObjectIdempotent("_component_template/" + templateName, settings, context);
    }

    Optional<ObjectNode> createIndexTemplate(
        String templateName,
        ObjectNode settings,
        IRfsContexts.ICheckedIdempotentPutRequestContext context
    ) {
        return createObjectIdempotent("_index_template/" + templateName, settings, context);
    }

    boolean hasLegacyTemplate(String templateName) {
        return hasObjectCheck("_template/" + templateName, null);
    }

    boolean hasComponentTemplate(String templateName) {
        return hasObjectCheck("_component_template/" + templateName, null);
    }

    boolean hasIndexTemplate(String templateName) {
        return hasObjectCheck("_index_template/" + templateName, null);
    }

    boolean hasIndex(String indexName) {
        return hasObjectCheck(indexName, null);
    }

    Optional<ObjectNode> createIndex(
        String indexName,
        ObjectNode settings,
        IRfsContexts.ICheckedIdempotentPutRequestContext context
    ) {
        return createObjectIdempotent(createIndexPath.apply(indexName), settings, context);
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
            var putResponse = client.putAsync(objectPath, settings.toString(), putRequestContext)
                .flatMap(resp -> {
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
            log.info(
                "Completed createObjectIdempotent for path={} in {} ms with statusCode={}",
                objectPath,
                duration,
                putResponse != null ? putResponse.statusCode : "null"
            );
            return Optional.of(settings);
        }
        log.debug("Object at path {} already exists, not attempting to create.", objectPath);
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
                if (resp.statusCode == HttpURLConnection.HTTP_NOT_FOUND
                    || resp.statusCode == HttpURLConnection.HTTP_OK) {
                    return Mono.just(resp);
                }
                String errorMessage = "Could not create object: " + objectPath + ". " + getString(resp);
                return Mono.error(new OperationFailed(errorMessage, resp));
            })
            .doOnError(e -> log.error(e.getMessage()))
            .retryWhen(CHECK_IF_ITEM_EXISTS_RETRY_STRATEGY)
            .block();

        long duration = System.currentTimeMillis() - startTime;
        log.info(
            "Completed hasObjectCheck for path={} with status={} in {} ms",
            objectPath,
            getResponse.statusCode,
            duration
        );
        assert getResponse != null : NULL_RESPONSE_ASSERTION;
        return getResponse.statusCode == HttpURLConnection.HTTP_OK;
    }

    void registerSnapshotRepo(
        String repoName,
        ObjectNode settings,
        IRfsContexts.ICreateSnapshotContext context,
        boolean verify
    ) {
        String targetPath = SNAPSHOT_PREFIX_STR + repoName + (verify ? "" : "?verify=false");
        log.info("Starting registerSnapshotRepo for repoName={}", repoName);
        long startTime = System.currentTimeMillis();
        var putResponse = client.putAsync(targetPath, settings.toString(), context.createRegisterRequest())
            .flatMap(resp -> {
                if (resp.statusCode == HttpURLConnection.HTTP_OK) {
                    return Mono.just(resp);
                }
                String errorMessage = "Could not register snapshot repo: " + targetPath + ". " + getString(resp);
                return Mono.error(new OperationFailed(errorMessage, resp));
            })
            .doOnError(e -> log.error(e.getMessage()))
            .retryWhen(SNAPSHOT_RETRY_STRATEGY)
            .block();
        long duration = System.currentTimeMillis() - startTime;
        log.info(
            "Completed registerSnapshotRepo for repoName={} in {} ms with statusCode={}",
            repoName,
            duration,
            putResponse != null ? putResponse.statusCode : "null"
        );
    }

    void createSnapshot(
        String repoName,
        String snapshotName,
        ObjectNode settings,
        IRfsContexts.ICreateSnapshotContext context
    ) {
        String targetPath = SNAPSHOT_PREFIX_STR + repoName + PATH_DELIMITER + snapshotName;
        log.info("Starting createSnapshot for repoName={}, snapshotName={}", repoName, snapshotName);
        long startTime = System.currentTimeMillis();
        var putResponse = client.putAsync(targetPath, settings.toString(), context.createSnapshotContext())
            .flatMap(resp -> {
                if (resp.statusCode == HttpURLConnection.HTTP_OK) {
                    return Mono.just(resp);
                }
                String errorMessage = "Could not create snapshot: " + targetPath + "." + getString(resp);
                return Mono.error(new OperationFailed(errorMessage, resp));
            })
            .doOnError(e -> log.error(e.getMessage()))
            .retryWhen(SNAPSHOT_RETRY_STRATEGY)
            .block();
        long duration = System.currentTimeMillis() - startTime;
        log.info(
            "Completed createSnapshot for repoName={}, snapshotName={} in {} ms with statusCode={}",
            repoName,
            snapshotName,
            duration,
            putResponse != null ? putResponse.statusCode : "null"
        );
    }

    Optional<ObjectNode> getSnapshotStatus(
        String repoName,
        String snapshotName,
        IRfsContexts.ICreateSnapshotContext context
    ) {
        String targetPath = SNAPSHOT_PREFIX_STR + repoName + PATH_DELIMITER + snapshotName;
        log.info("Starting getSnapshotStatus for repoName={}, snapshotName={}", repoName, snapshotName);
        long startTime = System.currentTimeMillis();
        var getResponse = client.getAsync(targetPath, context.createGetSnapshotContext())
            .flatMap(resp -> {
                if (resp.statusCode == HttpURLConnection.HTTP_OK
                    || resp.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                    return Mono.just(resp);
                }
                String errorMessage = "Could get status of snapshot: "
                    + targetPath
                    + ". Response Code: "
                    + resp.statusCode
                    + ", Response Body: "
                    + resp.body;
                return Mono.error(new OperationFailed(errorMessage, resp));
            })
            .doOnError(e -> log.error(e.getMessage()))
            .retryWhen(SNAPSHOT_RETRY_STRATEGY)
            .block();

        long duration = System.currentTimeMillis() - startTime;
        log.info(
            "Completed getSnapshotStatus for repoName={}, snapshotName={} in {} ms with statusCode={}",
            repoName,
            snapshotName,
            duration,
            getResponse != null ? getResponse.statusCode : "null"
        );

        assert getResponse != null : NULL_RESPONSE_ASSERTION;
        if (getResponse.statusCode == HttpURLConnection.HTTP_OK) {
            try {
                return Optional.of(OBJECT_MAPPER.readValue(getResponse.body, ObjectNode.class));
            } catch (Exception e) {
                throw new OperationFailed(
                    "Could not parse response for: _snapshot/" + repoName + "/" + snapshotName,
                    getResponse
                );
            }
        } else if (getResponse.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
            return Optional.empty();
        }
        throw new OperationFailed(
            "Should not have gotten here while parsing response for: _snapshot/" + repoName + "/" + snapshotName,
            getResponse
        );
    }

    HttpResponse refresh(IRfsContexts.IRequestContext context) {
        return client.get("_refresh", context);
    }
}
