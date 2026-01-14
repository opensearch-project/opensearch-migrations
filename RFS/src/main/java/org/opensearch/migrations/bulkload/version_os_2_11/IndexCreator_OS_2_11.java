package org.opensearch.migrations.bulkload.version_os_2_11;

import org.opensearch.migrations.AwarenessAttributeSettings;
import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.common.IncompatibleReplicaCountException;
import org.opensearch.migrations.bulkload.common.InvalidResponse;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
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
    protected final OpenSearchClient client;

    public CreationResult create(
        IndexMetadata index,
        MigrationMode mode,
        AwarenessAttributeSettings awarenessAttributeSettings,
        ICreateIndexContext context
    ) {
        var result = CreationResult.builder().name(index.getName());
        IndexMetadataData_OS_2_11 indexMetadata = new IndexMetadataData_OS_2_11(index.getRawJson(), index.getId(), index.getName());

        // Remove some settings which will cause errors if you try to pass them to the API
        ObjectNode settings = indexMetadata.getSettings();

        String[] problemSettings = { "creation_date", "provided_name", "uuid", "version", "index.mapping.single_type", "index.mapper.dynamic" };
        for (var field : problemSettings) {
            ObjectNodeUtils.removeFieldsByPath(settings, field);
        }

        ObjectNode mappings = (ObjectNode) indexMetadata.getMappings();
        if (mappings != null) {
            String[] problemMappingFields = { "_all" };
            for (var field : problemMappingFields) {
                ObjectNodeUtils.removeFieldsByPath(mappings, field);
            }
        }

        // Assemble the request body
        ObjectNode body = mapper.createObjectNode();
        body.set("aliases", indexMetadata.getAliases());
        body.set("mappings", mappings);
        body.set("settings", settings);

        try {
            createInner(index, mode, context, result, settings, body, awarenessAttributeSettings);
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
                             ObjectNode body,
                             AwarenessAttributeSettings awarenessAttributeSettings) throws IncompatibleReplicaCountException {
        // Create the index; it's fine if it already exists
        try {
            var alreadyExists = false;
            if (mode == MigrationMode.SIMULATE) {
                alreadyExists = client.hasIndex(index.getName());
            } else if (mode == MigrationMode.PERFORM) {
                alreadyExists = client.createIndex(index.getName(), body, context).isEmpty();
            }

            if (alreadyExists) {
                result.failureType(CreationFailureType.ALREADY_EXISTS);
            }

            if (mode == MigrationMode.SIMULATE) {
                checkForReplicaCountIncompatibility(settings, awarenessAttributeSettings);
            }
        } catch (InvalidResponse invalidResponse) {
            var potentialAwarenessAttributeException = invalidResponse.containsAwarenessAttributeException();
            if (potentialAwarenessAttributeException.isPresent()) {
                log.warn("Index creation failed due to awareness attribute exception: " + potentialAwarenessAttributeException.get());
                throw new IncompatibleReplicaCountException(potentialAwarenessAttributeException.get(), invalidResponse);
            }

            var illegalArguments = invalidResponse.getIllegalArguments();

            if (illegalArguments.isEmpty()) {
                log.debug("Cannot retry invalid response, there are no illegal arguments to remove.");
                throw invalidResponse;
            }

            for (var illegalArgument : illegalArguments) {
                if (!illegalArgument.startsWith("index.")) {
                    log.warn("Expecting all retryable errors to start with 'index.', instead saw " + illegalArgument);
                    throw invalidResponse;
                }

                var shortenedIllegalArgument = illegalArgument.replaceFirst("index.", "");
                log.debug("Removing setting '{}' from index '{}' settings", shortenedIllegalArgument, index.getName());
                ObjectNodeUtils.removeFieldsByPath(settings, shortenedIllegalArgument);
            }

            log.info("Reattempting creation of index '{}' after removing illegal arguments: {}", index.getName(), illegalArguments);
            client.createIndex(index.getName(), body, context);
        }
    }
}
