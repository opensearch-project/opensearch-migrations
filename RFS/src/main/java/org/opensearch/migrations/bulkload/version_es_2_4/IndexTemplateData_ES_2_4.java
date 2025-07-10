package org.opensearch.migrations.bulkload.version_es_2_4;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.models.AliasMetadata;
import org.opensearch.migrations.bulkload.models.IndexTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@EqualsAndHashCode(callSuper = false)
@ToString(callSuper = true)
public class IndexTemplateData_ES_2_4 extends IndexTemplate {

    private String name;
    private int order;
    private String template;
    private ObjectNode settings;
    private Map<String, JsonNode> mappings;
    private List<AliasMetadata> aliases;

    /**
     * Single private constructor used by both static factories
     */
    private IndexTemplateData_ES_2_4(
            String name,
            int order,
            String template,
            ObjectNode settings,
            Map<String, JsonNode> mappings,
            List<AliasMetadata> aliases
    ) {
        super(name, new ObjectMapper().createObjectNode());
        this.name = name;
        this.order = order;
        this.template = template;
        this.settings = settings;
        this.mappings = mappings;
        this.aliases = aliases;
    }

    /**
     * Static factory for JSON-deserialized (already-parsed) mappings
     */
    public static IndexTemplateData_ES_2_4 fromJsonParsed(
            String name,
            int order,
            String template,
            ObjectNode settings,
            Map<String, JsonNode> mappings,
            List<AliasMetadata> aliases
    ) {
        return new IndexTemplateData_ES_2_4(name, order, template, settings, mappings, aliases);
    }

    /**
     * Static factory for REPO/BINARY path (mappings as raw byte[])
     */
    public static IndexTemplateData_ES_2_4 fromRepoBinary(
            String name,
            int order,
            String template,
            Map<String, String> settingsMap,
            Map<String, byte[]> mappingsBytes,
            List<AliasMetadata> aliases
    ) {
        Map<String, JsonNode> parsedMappings = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper();
        mappingsBytes.forEach((key, value) -> {
            try {
                JsonNode parsed = mapper.readTree(value);
                parsedMappings.put(key, parsed);
            } catch (Exception e) {
                throw new RuntimeException("Error parsing mapping bytes for type: " + key, e);
            }
        });
        ObjectNode settingsNode = mapper.createObjectNode();
        if (settingsMap != null) {
            settingsMap.forEach(settingsNode::put);
        }
        return new IndexTemplateData_ES_2_4(name, order, template, settingsNode, parsedMappings, aliases);
    }

    /**
     * Secondary constructor for reading directly from Smile or JSON trees
     */
    public IndexTemplateData_ES_2_4(String name, JsonNode json) {
        super(name, (ObjectNode) json);
        this.name = name;
        this.order = json.get("order").asInt();
        this.template = json.hasNonNull("template") ? json.get("template").asText() : null;

        // Settings
        if (json.has("settings") && json.get("settings").isObject()) {
            this.settings = (ObjectNode) json.get("settings").deepCopy();
        } else {
            this.settings = new ObjectMapper().createObjectNode();
        }

        // Mappings
        this.mappings = new HashMap<>();
        if (json.has("mappings") && json.get("mappings").isObject()) {
            var fieldIterator = json.get("mappings").fields();
            while (fieldIterator.hasNext()) {
                var entry = fieldIterator.next();
                this.mappings.put(entry.getKey(), entry.getValue());
            }
        }

        // Aliases
        this.aliases = new ArrayList<>();
        if (json.has("aliases") && json.get("aliases").isObject()) {
            var fieldsIterator = json.get("aliases").fields();
            while (fieldsIterator.hasNext()) {
                var entry = fieldsIterator.next();
                String aliasName = entry.getKey();
                JsonNode aliasBody = entry.getValue();
                this.aliases.add(AliasMetadata.fromJsonWithName(aliasName, aliasBody));
            }
        }
    }

    public ObjectNode toObjectNode() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();

        // Opensearch fails to recognize "name" or "template" as top-level string, hence they have been dropped.
        root.put("order", order);

        // Convert "template" string into "index_patterns" array
        if (template != null) {
            ArrayNode indexPatternsNode = mapper.createArrayNode();
            indexPatternsNode.add(template);
            root.set("index_patterns", indexPatternsNode);
        }

        // Settings
        root.set("settings", settings.deepCopy());

        // Mappings
        ObjectNode mappingsNode = mapper.createObjectNode();
        for (Map.Entry<String, JsonNode> entry : mappings.entrySet()) {
            mappingsNode.set(entry.getKey(), entry.getValue());
        }
        root.set("mappings", mappingsNode);

        // Aliases
        ObjectNode aliasesNode = mapper.createObjectNode();
        for (AliasMetadata alias : aliases) {
            ObjectNode aliasObj = mapper.createObjectNode();
            if (alias.getIndexRouting() != null) aliasObj.put("indexRouting", alias.getIndexRouting());
            if (alias.getSearchRouting() != null) aliasObj.put("searchRouting", alias.getSearchRouting());
            if (alias.getWriteIndex() != null) aliasObj.put("writeIndex", alias.getWriteIndex());
            if (alias.getFilter() != null) aliasObj.put("filter", alias.getFilter());

            ObjectNode settingsObj = mapper.createObjectNode();
            alias.getSettings().forEach(settingsObj::put);
            aliasObj.set("settings", settingsObj);

            aliasesNode.set(alias.getAlias(), aliasObj);
        }
        root.set("aliases", aliasesNode);

        return root;
    }
}
