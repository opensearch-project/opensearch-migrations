package org.opensearch.migrations.bulkload.version_es_2_4;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.opensearch.migrations.bulkload.models.AliasMetadata;
import org.opensearch.migrations.bulkload.models.CompressedMapping;
import org.opensearch.migrations.bulkload.models.IndexMetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IndexMetadataData_ES_2_4 implements IndexMetadata {

    private String index;
    private long version;
    private int routingNumShards;
    private byte state;
    private ObjectNode settings;
    private long[] primaryTerms;
    private List<CompressedMapping> mappings;
    private List<AliasMetadata> aliases;

    public IndexMetadataData_ES_2_4(String indexName, JsonNode json) {
        this.index = indexName;
        this.version = json.get("version").asLong();
        this.routingNumShards = json.get("routing_num_shards").asInt();
        this.state = (byte) json.get("state").asInt();

        ObjectMapper mapper = new ObjectMapper();

        // Settings
        if (json.has("settings") && json.get("settings").isObject()) {
            this.settings = (ObjectNode) json.get("settings").deepCopy();
        } else {
            this.settings = mapper.createObjectNode();
        }

        // Primary terms
        if (json.has("primary_terms") && json.get("primary_terms").isArray()) {
            var array = json.get("primary_terms");
            this.primaryTerms = new long[array.size()];
            for (int i = 0; i < array.size(); i++) {
                this.primaryTerms[i] = array.get(i).asLong();
            }
        } else {
            this.primaryTerms = new long[0];
        }

        // Mappings
        this.mappings = new ArrayList<>();
        if (json.has("mappings") && json.get("mappings").isArray()) {
            for (var m : json.get("mappings")) {
                this.mappings.add(new CompressedMapping(
                        m.get("type").asText(),
                        Base64.getDecoder().decode(m.get("compressed").asText())
                ));
            }
        }

        // Aliases
        this.aliases = new ArrayList<>();
        if (json.has("aliases") && json.get("aliases").isObject()) {
            var it = json.get("aliases").fields();
            while (it.hasNext()) {
                var entry = it.next();
                this.aliases.add(AliasMetadata.fromJsonWithName(entry.getKey(), entry.getValue()));
            }
        }
    }

    @Override
    public JsonNode getAliases() {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode array = mapper.createArrayNode();
        for (AliasMetadata alias : aliases) {
            ObjectNode obj = mapper.createObjectNode();
            obj.put("alias", alias.getAlias());
            if (alias.getIndexRouting() != null) obj.put("indexRouting", alias.getIndexRouting());
            if (alias.getSearchRouting() != null) obj.put("searchRouting", alias.getSearchRouting());
            if (alias.getWriteIndex() != null) obj.put("writeIndex", alias.getWriteIndex());
            if (alias.getFilter() != null) obj.put("filter", alias.getFilter());

            ObjectNode settingsNode = mapper.createObjectNode();
            alias.getSettings().forEach(settingsNode::put);
            obj.set("settings", settingsNode);

            array.add(obj);
        }
        return array;
    }

    @Override
    public String getId() {
        return index;
    }

    @Override
    public JsonNode getMappings() {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode array = mapper.createArrayNode();
        for (var m : mappings) {
            ObjectNode obj = mapper.createObjectNode();
            obj.put("type", m.getType());
            obj.put("compressed", Base64.getEncoder().encodeToString(m.getSource()));
            array.add(obj);
        }
        return array;
    }

    @Override
    public int getNumberOfShards() {
        return routingNumShards;
    }

    @Override
    public JsonNode getSettings() {
        return settings.deepCopy();
    }

    @Override
    public IndexMetadata deepCopy() {
        ObjectMapper mapper = new ObjectMapper();
        return new IndexMetadataData_ES_2_4(
            index,
            version,
            routingNumShards,
            state,
            settings.deepCopy(),
            primaryTerms.clone(),
            new ArrayList<>(mappings),
            new ArrayList<>(aliases)
        );
    }

    @Override
    public ObjectNode getRawJson() {
        return toObjectNode();
    }

    @Override
    public String getName() {
        return index;
    }

    public ObjectNode toObjectNode() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode obj = mapper.createObjectNode();
        obj.put("index", index);
        obj.put("version", version);
        obj.put("routing_num_shards", routingNumShards);
        obj.put("state", state);

        obj.set("settings", settings.deepCopy());

        // Primary terms
        ArrayNode primaryTermsArray = mapper.createArrayNode();
        for (long term : primaryTerms) {
            primaryTermsArray.add(term);
        }
        obj.set("primary_terms", primaryTermsArray);

        // Mappings
        ArrayNode mappingsArray = mapper.createArrayNode();
        for (CompressedMapping m : mappings) {
            ObjectNode mappingObj = mapper.createObjectNode();
            mappingObj.put("type", m.getType());
            mappingObj.put("compressed", Base64.getEncoder().encodeToString(m.getSource()));
            mappingsArray.add(mappingObj);
        }
        obj.set("mappings", mappingsArray);

        // Aliases
        ObjectNode aliasesNode = mapper.createObjectNode();
        for (AliasMetadata alias : aliases) {
            ObjectNode aliasObj = mapper.createObjectNode();
            aliasObj.put("alias", alias.getAlias());
            if (alias.getIndexRouting() != null) aliasObj.put("indexRouting", alias.getIndexRouting());
            if (alias.getSearchRouting() != null) aliasObj.put("searchRouting", alias.getSearchRouting());
            if (alias.getWriteIndex() != null) aliasObj.put("writeIndex", alias.getWriteIndex());
            if (alias.getFilter() != null) aliasObj.put("filter", alias.getFilter());

            ObjectNode aliasSettings = mapper.createObjectNode();
            alias.getSettings().forEach(aliasSettings::put);
            aliasObj.set("settings", aliasSettings);

            aliasesNode.set(alias.getAlias(), aliasObj);
        }
        obj.set("aliases", aliasesNode);

        return obj;
    }
}
