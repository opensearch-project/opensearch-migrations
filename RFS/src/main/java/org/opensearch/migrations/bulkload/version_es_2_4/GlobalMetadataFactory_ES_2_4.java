package org.opensearch.migrations.bulkload.version_es_2_4;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.models.IndexTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GlobalMetadataFactory_ES_2_4 implements GlobalMetadata.Factory {

    private final SnapshotRepo.Provider repoDataProvider;

    public GlobalMetadataFactory_ES_2_4(SnapshotRepo.Provider repoDataProvider) {
        this.repoDataProvider = repoDataProvider;
    }

    @Override
    public SmileFactory getSmileFactory() {
        return new SmileFactory();
    }

    @Override
    public SnapshotRepo.Provider getRepoDataProvider() {
        return repoDataProvider;
    }

    @Override
    public GlobalMetadata fromJsonNode(JsonNode root) {
        log.debug("[ES 2.4] Parsed root JSON = {}", root.toPrettyString());

        JsonNode metadataRoot = root.get("meta-data");
        if (metadataRoot == null || !metadataRoot.isObject()) {
            throw new IllegalArgumentException("Expected 'meta-data' object in root!");
        }

        GlobalMetadataData_ES_2_4 parsed = new GlobalMetadataData_ES_2_4();
        parsed.setClusterUUID(metadataRoot.get("cluster_uuid").asText());
        parsed.setTransientSettings(readSettingsFromJson(metadataRoot.get("transient_settings")));
        parsed.setPersistentSettings(readSettingsFromJson(metadataRoot.get("persistent_settings")));

        parsed.setIndices(readIndicesFromJson(metadataRoot.get("indices")));
        parsed.setTemplates(readTemplatesFromJson(metadataRoot.get("templates")));
        parsed.setCustoms(readCustomsFromJson(metadataRoot.get("customs")));

        return parsed;
    }

    private static Map<String, String> readSettingsFromJson(JsonNode settingsNode) {
        Map<String, String> map = new HashMap<>();
        if (settingsNode != null && settingsNode.isObject()) {
            settingsNode.fields().forEachRemaining(entry -> map.put(entry.getKey(), entry.getValue().asText()));
        }
        return map;
    }

    private static List<IndexMetadata> readIndicesFromJson(JsonNode indicesNode) {
        List<IndexMetadata> list = new ArrayList<>();
        if (indicesNode != null && indicesNode.isObject()) {
            indicesNode.fields().forEachRemaining(entry -> {
                String indexName = entry.getKey();
                JsonNode meta = entry.getValue();
                list.add(new IndexMetadataData_ES_2_4(indexName, meta));
            });
        }
        return list;
    }

    private static List<IndexTemplate> readTemplatesFromJson(JsonNode templatesNode) {
        List<IndexTemplate> list = new ArrayList<>();
        if (templatesNode != null && templatesNode.isObject()) {
            templatesNode.fields().forEachRemaining(entry -> {
                String templateName = entry.getKey();
                JsonNode templateNode = entry.getValue();
                list.add(new IndexTemplate(templateName, (ObjectNode) templateNode));
            });
        }
        return list;
    }

    private static Map<String, Object> readCustomsFromJson(JsonNode customsNode) {
        Map<String, Object> customs = new HashMap<>();
        if (customsNode != null && customsNode.isObject()) {
            customsNode.properties().forEach(entry -> customs.put(entry.getKey(), entry.getValue()));
        }
        return customs;
    }
}
