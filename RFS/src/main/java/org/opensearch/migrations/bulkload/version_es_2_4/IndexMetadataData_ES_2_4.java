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

import static org.opensearch.migrations.bulkload.version_es_2_4.ElasticsearchConstants_ES_2_4.*;

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

        parseSettings(json);
        parsePrimaryTerms(json);
        parseMappings(json);
        parseAliases(json);
    }

    private void parseSettings(JsonNode json) {
        ObjectMapper mapper = new ObjectMapper();
        if (json.has(FIELD_SETTINGS) && json.get(FIELD_SETTINGS).isObject()) {
            this.settings = (ObjectNode) json.get(FIELD_SETTINGS).deepCopy();
        } else {
            this.settings = mapper.createObjectNode();
        }
    }

    private void parsePrimaryTerms(JsonNode json) {
        if (json.has(FIELD_PRIMARY_TERMS) && json.get(FIELD_PRIMARY_TERMS).isArray()) {
            var array = json.get(FIELD_PRIMARY_TERMS);
            this.primaryTerms = new long[array.size()];
            for (int i = 0; i < array.size(); i++) {
                this.primaryTerms[i] = array.get(i).asLong();
            }
        } else {
            this.primaryTerms = new long[0];
        }
    }

    private void parseMappings(JsonNode json) {
        this.mappings = new ArrayList<>();
        if (json.has(FIELD_MAPPINGS) && json.get(FIELD_MAPPINGS).isArray()) {
            for (var m : json.get(FIELD_MAPPINGS)) {
                this.mappings.add(new CompressedMapping(
                        m.get("type").asText(),
                        Base64.getDecoder().decode(m.get(FIELD_COMPRESSED).asText())
                ));
            }
        }
    }

    private void parseAliases(JsonNode json) {
        this.aliases = new ArrayList<>();
        if (json.has(FIELD_ALIASES) && json.get(FIELD_ALIASES).isObject()) {
            json.get(FIELD_ALIASES).fieldNames().forEachRemaining(aliasName -> {
                JsonNode aliasNode = json.get(FIELD_ALIASES).get(aliasName);
                this.aliases.add(AliasMetadata.fromJsonWithName(aliasName, aliasNode));
            });
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
            obj.set(FIELD_SETTINGS, settingsNode);

            array.add(obj);
        }
        return array;
    }

    @Override
    public String getId() {
        return getName();
    }

    @Override
    public String getName() {
        return index;
    }

    @Override
    public JsonNode getMappings() {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode array = mapper.createArrayNode();
        for (var m : mappings) {
            ObjectNode obj = mapper.createObjectNode();
            obj.put("type", m.getType());
            obj.put(FIELD_COMPRESSED, Base64.getEncoder().encodeToString(m.getSource()));
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

    public ObjectNode toObjectNode() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode obj = mapper.createObjectNode();
        obj.put("index", index);
        obj.put("version", version);
        obj.put("routing_num_shards", routingNumShards);
        obj.put("state", state);

        obj.set(FIELD_SETTINGS, settings.deepCopy());

        // Primary terms
        ArrayNode primaryTermsArray = mapper.createArrayNode();
        for (long term : primaryTerms) {
            primaryTermsArray.add(term);
        }
        obj.set(FIELD_PRIMARY_TERMS, primaryTermsArray);

        // Mappings
        ArrayNode mappingsArray = mapper.createArrayNode();
        for (CompressedMapping m : mappings) {
            ObjectNode mappingObj = mapper.createObjectNode();
            mappingObj.put("type", m.getType());
            mappingObj.put(FIELD_COMPRESSED, Base64.getEncoder().encodeToString(m.getSource()));
            mappingsArray.add(mappingObj);
        }
        obj.set(FIELD_MAPPINGS, mappingsArray);

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
            aliasObj.set(FIELD_SETTINGS, aliasSettings);

            aliasesNode.set(alias.getAlias(), aliasObj);
        }
        obj.set(FIELD_ALIASES, aliasesNode);

        return obj;
    }
}
