package com.rfs.version_os_2_11;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts;

import com.rfs.common.InvalidResponse;
import com.rfs.common.OpenSearchClient;
import com.rfs.models.IndexMetadata;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class IndexCreator_OS_2_11 {
    private static final ObjectMapper mapper = new ObjectMapper();
    protected final OpenSearchClient client;

    public IndexCreator_OS_2_11(OpenSearchClient client) {
        this.client = client;
    }

    public Optional<ObjectNode> create(
        IndexMetadata index,
        String indexName,
        String indexId,
        IMetadataMigrationContexts.ICreateIndexContext context
    ) {
        IndexMetadataData_OS_2_11 indexMetadata = new IndexMetadataData_OS_2_11(index.rawJson(), indexId, indexName);

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
            return client.createIndex(indexName, body, context);
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

            log.info("Reattempting creation of index '" + indexName + "' after removing illegal arguments; " + illegalArguments);
            return client.createIndex(indexName, body, context);
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
