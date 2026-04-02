package org.opensearch.migrations.bulkload.version_os_2_11;

import java.util.Map;

import org.opensearch.migrations.AwarenessAttributeSettings;
import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.common.IncompatibleReplicaCountException;
import org.opensearch.migrations.bulkload.common.InvalidResponse;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.RfsException;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.CreationResult.CreationFailureType;
import org.opensearch.migrations.metadata.CreationResult.CreationResultBuilder;
import org.opensearch.migrations.metadata.IndexCreator;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.ICreateIndexContext;
import org.opensearch.migrations.parsing.ObjectNodeUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public class IndexCreator_OS_2_11 implements IndexCreator {
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Maps top-level index metadata fields from the snapshot to their corresponding
     * create index API setting names. The snapshot stores these at the top level of
     * the index metadata JSON, but the create index API expects them as settings.
     */
    static final Map<String, String> METADATA_TO_SETTINGS = Map.of(
        "routing_num_shards", "number_of_routing_shards"
    );

    protected final OpenSearchClient client;

    public CreationResult create(
        IndexMetadata index,
        MigrationMode mode,
        AwarenessAttributeSettings awarenessAttributeSettings,
        ICreateIndexContext context
    ) {
        var result = CreationResult.builder().name(index.getName());
        IndexMetadataData_OS_2_11 indexMetadata = new IndexMetadataData_OS_2_11(index.getRawJson(), index.getId(), index.getName());

        ObjectNode settings = indexMetadata.getSettings();

        ObjectNode mappings = indexMetadata.getMappings();

        // Copy top-level metadata fields into settings.
        // The source snapshot stores these at the top level of the index metadata JSON,
        // but the create index API expects them as settings. Without this mapping,
        // the target gets different defaults (e.g., routing_num_shards), causing issues
        // like documents with custom routing landing on different shards.
        var rawJson = index.getRawJson();
        METADATA_TO_SETTINGS.forEach((metadataField, settingName) -> {
            var value = rawJson.path(metadataField);
            if (!value.isMissingNode()) {
                settings.set(settingName, value);
                log.atDebug()
                    .setMessage("Copied metadata field '{}' as setting '{}' = {}")
                    .addArgument(metadataField)
                    .addArgument(settingName)
                    .addArgument(value)
                    .log();
            }
        });

        // Assemble the request body
        ObjectNode body = mapper.createObjectNode();
        body.set("aliases", indexMetadata.getAliases());
        body.set("mappings", mappings);
        body.set("settings", settings);

        try {
            createInner(index, mode, context, result, settings, mappings, body, awarenessAttributeSettings);
        } catch (IncompatibleReplicaCountException e) {
            result.failureType(CreationFailureType.INCOMPATIBLE_REPLICA_COUNT_FAILURE);
            result.exception(e);
        } catch (Exception e) {
            result.failureType(CreationFailureType.TARGET_CLUSTER_FAILURE);
            result.exception(e);
        }
        return result.build();
    }

    private void checkForReplicaCountIncompatibility(ObjectNode settings, AwarenessAttributeSettings awarenessAttributeSettings) throws IncompatibleReplicaCountException {
        if (!awarenessAttributeSettings.isBalanceEnabled()) {
            return;
        }
        var awarenessAttributes = awarenessAttributeSettings.getNumberOfAttributeValues();
        String replicaSetting = "number_of_replicas";
        if (!settings.hasNonNull(replicaSetting)) {
            return;
        }
        var replicaCount = settings.get(replicaSetting).asInt();
        // To be compatible with the number of awareness attributes (usually zones), the awareness attribute must be divisible
        // by the replica count + 1
        if ((replicaCount + 1) % awarenessAttributes != 0) {
            var replicaCountMessage = ("A replica count of %d is not compatible with %d awareness attributes (usually zones). " +
                "The metadata migration tool can automatically remedy this by increasing the replica count to a compatible number " +
                "if run with the command line parameter `--cluster-awareness-attributes %d`").formatted(replicaCount, awarenessAttributes, awarenessAttributes);
            throw new IncompatibleReplicaCountException(replicaCountMessage, null);
        }
    }

    private void createInner(IndexMetadata index,
                             MigrationMode mode,
                             ICreateIndexContext context,
                             CreationResultBuilder result,
                             ObjectNode settings,
                             ObjectNode mappings,
                             ObjectNode body,
                             AwarenessAttributeSettings awarenessAttributeSettings) throws IncompatibleReplicaCountException {
        // Create the index; it's fine if it already exists
        var alreadyExists = false;
        if (mode == MigrationMode.SIMULATE) {
            alreadyExists = client.hasIndex(index.getName());
        } else if (mode == MigrationMode.PERFORM) {
            alreadyExists = createWithRetry(index.getName(), body, settings, mappings, context);
        }

        if (alreadyExists) {
            result.failureType(CreationFailureType.INDEX_ALREADY_EXISTS);
        }

        if (mode == MigrationMode.SIMULATE) {
            checkForReplicaCountIncompatibility(settings, awarenessAttributeSettings);
        }
    }

    /**
     * Attempts to create the index, retrying by removing unknown settings reported by the cluster.
     * The cluster may report unknown settings in batches, so this loops until either the index is
     * created successfully or no more removable illegal arguments are found.
     *
     * @return true if the index already existed, false if it was created
     */
    private boolean createWithRetry(String indexName, ObjectNode body, ObjectNode settings, ObjectNode mappings, ICreateIndexContext context) throws IncompatibleReplicaCountException {
        while (true) {
            try {
                return client.createIndex(indexName, body, context).isEmpty();
            } catch (InvalidResponse invalidResponse) {
                handleInvalidResponse(invalidResponse, indexName, settings, mappings);
            } catch (Exception e) {
                handleWrappedException(e, indexName, settings, mappings);
            }
        }
    }

    /**
     * Handles exceptions that may wrap an InvalidResponse at any depth in the cause chain.
     * Reactor's retryWhen may wrap the original exception, so we walk the chain to find it.
     */
    private void handleWrappedException(Exception e, String indexName, ObjectNode settings, ObjectNode mappings) throws IncompatibleReplicaCountException {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidResponse ir) {
                handleInvalidResponse(ir, indexName, settings, mappings);
                return;
            }
        }
        log.warn("Unexpected exception type during index creation: {} - {}", e.getClass().getName(), e.getMessage());
        throw new RfsException("Unexpected exception during index creation", e);
    }

    private void handleInvalidResponse(InvalidResponse invalidResponse, String indexName, ObjectNode settings, ObjectNode mappings) throws IncompatibleReplicaCountException {
        var potentialAwarenessAttributeException = invalidResponse.containsAwarenessAttributeException();
        if (potentialAwarenessAttributeException.isPresent()) {
            log.warn("Index creation failed due to awareness attribute exception: " + potentialAwarenessAttributeException.get());
            throw new IncompatibleReplicaCountException(potentialAwarenessAttributeException.get(), invalidResponse);
        }

        var illegalArguments = invalidResponse.getIllegalArguments();

        if (!illegalArguments.isEmpty()) {
            for (var illegalArgument : illegalArguments) {
                if (!illegalArgument.startsWith("index.")) {
                    log.warn("Expecting all retryable errors to start with 'index.', instead saw " + illegalArgument);
                    throw invalidResponse;
                }

                var shortenedIllegalArgument = illegalArgument.replaceFirst("index.", "");
                log.debug("Removing setting '{}' from index '{}' settings", shortenedIllegalArgument, indexName);
                ObjectNodeUtils.removeFieldsByPath(settings, shortenedIllegalArgument);
            }

            log.info("Reattempting creation of index '{}' after removing illegal arguments: {}", indexName, illegalArguments);
            return;
        }

        var unsupportedMappingParams = invalidResponse.getUnsupportedMappingParameters();

        if (!unsupportedMappingParams.isEmpty()) {
            for (var param : unsupportedMappingParams) {
                log.debug("Removing unsupported mapping parameter '{}' from index '{}'", param, indexName);
                ObjectNodeUtils.removeFieldsByPath(mappings, param);
            }

            log.info("Reattempting creation of index '{}' after removing unsupported mapping parameters: {}", indexName, unsupportedMappingParams);
            return;
        }

        log.debug("Cannot retry invalid response, there are no illegal arguments or unsupported mapping parameters to remove.");
        throw invalidResponse;
    }
}
