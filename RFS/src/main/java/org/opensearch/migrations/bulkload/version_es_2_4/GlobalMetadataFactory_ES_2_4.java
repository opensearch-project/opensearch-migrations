package org.opensearch.migrations.bulkload.version_es_2_4;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.models.AliasMetadata;
import org.opensearch.migrations.bulkload.models.CompressedMapping;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.models.IndexTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GlobalMetadataFactory_ES_2_4 implements GlobalMetadata.Factory {

    private final SnapshotRepo.Provider repoDataProvider;

    public GlobalMetadataFactory_ES_2_4(SnapshotRepo.Provider repoDataProvider) {
        this.repoDataProvider = repoDataProvider;
    }

    @FunctionalInterface
    public interface CustomMetadataReader_ES_2_4 {
        Object readFrom(ByteArrayStreamInput_ES_2_4 in) throws IOException;
    }

    /**
     * Registry of known custom readers
     */
    private static final Map<String, CustomMetadataReader_ES_2_4> CUSTOM_READERS = new HashMap<>();
    static {
        CUSTOM_READERS.put("repositories", GlobalMetadataFactory_ES_2_4::readRepositoriesMetadata);
        // Add more custom types here later!
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
        log.info("[ES 2.4] Parsed root JSON = {}", root.toPrettyString());
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

    private static Object readRepositoriesMetadata(ByteArrayStreamInput_ES_2_4 in) throws IOException {
        int numRepositories = in.readVInt();
        List<Map<String, Object>> repositories = new ArrayList<>();
        for (int i = 0; i < numRepositories; i++) {
            String name = in.readString();
            String type = in.readString();
            Map<String, String> settings = readSettings(in);
            long generation = in.readLong();

            Map<String, Object> repo = new HashMap<>();
            repo.put("name", name);
            repo.put("type", type);
            repo.put("settings", settings);
            repo.put("generation", generation);

            repositories.add(repo);
        }
        return repositories;
    }

    public static IndexMetadataData_ES_2_4 readIndexMetadata(ByteArrayStreamInput_ES_2_4 in) throws IOException {
        String index = in.readString();
        long version = in.readLong();
        int routingNumShards = in.readInt();
        byte state = in.readByte();

        Map<String, String> settings = readSettings(in);
        long[] primaryTerms = in.readVLongArray();

        int mappingsSize = in.readVInt();
        List<CompressedMapping> mappings = new ArrayList<>();
        for (int i = 0; i < mappingsSize; i++) {
            String type = in.readString();
            byte[] source = in.readByteArray();
            mappings.add(new CompressedMapping(type, source));
        }

        int aliasesSize = in.readVInt();
        List<AliasMetadata> aliases = new ArrayList<>();
        for (int i = 0; i < aliasesSize; i++) {
            aliases.add(readAliasMetadata(in));
        }

        int customsSize = in.readVInt();
        for (int i = 0; i < customsSize; i++) {
            in.readString();     // key
            in.readByteArray();  // value
        }

        int inSyncAllocSize = in.readVInt();
        for (int i = 0; i < inSyncAllocSize; i++) {
            in.readVInt(); // shardId
            int setSize = in.readVInt();
            for (int j = 0; j < setSize; j++) {
                in.readString();
            }
        }

        return new IndexMetadataData_ES_2_4(
            index, version, routingNumShards, state,
            convertSettingsToObjectNode(settings), primaryTerms, mappings, aliases
        );
    }

    private static IndexTemplateData_ES_2_4 readIndexTemplate(ByteArrayStreamInput_ES_2_4 in) throws IOException {
        String name = in.readString();
        int order = in.readInt();
        String template = in.readOptionalString();
        Map<String, String> settings = readSettings(in);

        int mappingsSize = in.readVInt();
        Map<String, byte[]> mappings = new HashMap<>();
        for (int i = 0; i < mappingsSize; i++) {
            String type = in.readString();
            byte[] source = in.readByteArray();
            mappings.put(type, source);
        }

        int aliasesSize = in.readVInt();
        List<AliasMetadata> aliases = new ArrayList<>();
        for (int i = 0; i < aliasesSize; i++) {
            aliases.add(readAliasMetadata(in));
        }

        int customSize = in.readVInt();
        for (int i = 0; i < customSize; i++) {
            in.readString();
            in.readByteArray();
            // ignore for now
        }

        return IndexTemplateData_ES_2_4.fromRepoBinary(name, order, template, settings, mappings, aliases);
    }

    private static AliasMetadata readAliasMetadata(ByteArrayStreamInput_ES_2_4 in) throws IOException {
        String alias = in.readString();
        String indexRouting = in.readOptionalString();
        String searchRouting = in.readOptionalString();
        Boolean writeIndex = in.readOptionalBoolean();
        String filter = in.readOptionalString();
        Map<String, String> settings = readSettings(in);

        return new AliasMetadata(alias, indexRouting, searchRouting, writeIndex, filter, settings);
    }

    private static Map<String, String> readSettings(ByteArrayStreamInput_ES_2_4 in) throws IOException {
        int size = in.readVInt();
        Map<String, String> settings = new HashMap<>();
        for (int i = 0; i < size; i++) {
            String key = in.readString();
            String value = in.readString();
            settings.put(key, value);
        }
        return settings;
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
                list.add(new IndexTemplateData_ES_2_4(templateName, templateNode));
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

    private static ObjectNode convertSettingsToObjectNode(Map<String, String> settings) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        if (settings != null) {
            settings.forEach(node::put);
        }
        return node;
    }
}
