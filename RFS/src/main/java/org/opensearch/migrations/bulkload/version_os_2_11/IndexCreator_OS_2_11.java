package org.opensearch.migrations.bulkload.version_os_2_11;

import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.common.InvalidResponse;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.CreationResult.CreationFailureType;
import org.opensearch.migrations.metadata.CreationResult.CreationResultBuilder;
import org.opensearch.migrations.metadata.IndexCreator;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.ICreateIndexContext;

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
        ICreateIndexContext context
    ) {
        var result = CreationResult.builder().name(index.getName());
        IndexMetadataData_OS_2_11 indexMetadata = new IndexMetadataData_OS_2_11(index.getRawJson(), index.getId(), index.getName());

        // Remove some settings which will cause errors if you try to pass them to the API
        ObjectNode settings = indexMetadata.getSettings();

        String[] problemSettings = { "creation_date", "provided_name", "uuid", "version", "index.mapping.single_type", "index.mapper.dynamic" };
        for (var field : problemSettings) {
            settings.remove(field);
        }

        // Assemble the request body
        ObjectNode body = mapper.createObjectNode();
        body.set("aliases", indexMetadata.getAliases());
        body.set("mappings", index.getMappings());
        body.set("settings", settings);

        try {
            createInner(index, mode, context, result, settings, body);
        } catch (Exception e) {
            result.failureType(CreationFailureType.TARGET_CLUSTER_FAILURE);
            result.exception(e);
        }
        return result.build();
    }

    private void createInner(IndexMetadata index,
                             MigrationMode mode,
                             ICreateIndexContext context,
                             CreationResultBuilder result,
                             ObjectNode settings,
                             ObjectNode body) {
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
        } catch (InvalidResponse invalidResponse) {
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
                removeFieldsByPath(settings, shortenedIllegalArgument);
            }

            log.info("Reattempting creation of index '" + index.getName() + "' after removing illegal arguments; " + illegalArguments);
            client.createIndex(index.getName(), body, context);
        }
    }

    private void removeFieldsByPath(ObjectNode node, String path) {
        var pathParts = path.split("\\.");

        if (pathParts.length == 1) {
            node.remove(pathParts[0]);
            return;
        }

        var currentNode = node;
        for (int i = 0; i < pathParts.length - 1; i++) {
            var nextNode = currentNode.get(pathParts[i]);
            if (nextNode != null && nextNode.isObject()) {
                currentNode = (ObjectNode) nextNode;
            } else {
                return;
            }
        }
        currentNode.remove(pathParts[pathParts.length - 1]);
    }
}
