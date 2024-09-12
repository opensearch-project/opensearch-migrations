package com.rfs.version_os_2_11;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.metadata.IndexCreator;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.ICreateIndexContext;

import com.rfs.common.InvalidResponse;
import com.rfs.common.OpenSearchClient;
import com.rfs.models.IndexMetadata;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public class IndexCreator_OS_2_11 implements IndexCreator {
    private static final ObjectMapper mapper = new ObjectMapper();
    protected final OpenSearchClient client;

    public boolean create(
        IndexMetadata index,
        MigrationMode mode,
        ICreateIndexContext context
    ) {
        IndexMetadataData_OS_2_11 indexMetadata = new IndexMetadataData_OS_2_11(index.rawJson(), index.getId(), index.getName());

        // Remove some settings which will cause errors if you try to pass them to the API
        ObjectNode settings = indexMetadata.getSettings();

        String[] problemFields = { "creation_date", "provided_name", "uuid", "version" };
        for (String field : problemFields) {
            settings.remove(field);
        }

        // Assemble the request body
        ObjectNode body = mapper.createObjectNode();
        body.set("aliases", indexMetadata.getAliases());
        body.set("mappings", index.getMappings());
        body.set("settings", settings);

        // Create the index; it's fine if it already exists
        try {
            switch (mode) {
                case SIMULATE:
                    return !client.hasIndex(index.getName());
                case PERFORM:
                    return client.createIndex(index.getName(), body, context).isPresent();
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
            return client.createIndex(index.getName(), body, context).isPresent();
        }
        return false;
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
