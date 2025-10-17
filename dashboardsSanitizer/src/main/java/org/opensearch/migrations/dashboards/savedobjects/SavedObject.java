package org.opensearch.migrations.dashboards.savedobjects;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.semver4j.Semver;

@Slf4j
public class SavedObject {

    protected static final ObjectMapper objectMapper = new ObjectMapper();

    @Getter
    private final String id;

    @Getter
    private final Semver exportedVersion;

    @Getter
    protected Semver version;

    @Getter
    protected String objectType;

    @ToString.Exclude
    protected ObjectNode json;

    protected ObjectNode originalJson;

    @Getter
    protected List<Reference> references = new ArrayList<>();

    public SavedObject(ObjectNode json) {
        this(json, json.get("type").asText());
    }

    public SavedObject(ObjectNode json, String objectType) {
        this.json = json;
        this.originalJson = json.deepCopy();
        this.objectType = objectType;
        this.id = json.get("id").asText();
        this.exportedVersion = determineExportedVersion();
        this.version = Semver.parse(exportedVersion.getVersion());

        json.get("references").forEach(reference -> {
            this.references.add(new Reference((ObjectNode) reference));
        });
    }

    public ObjectNode attributes() {
        return json.withObject("attributes");
    }

    public String attributeValue(String attribute) {
        final ObjectNode attrs = this.attributes();
        if (!attrs.has(attribute)) {
            return null;
        }

        return attrs.get(attribute).asText();
    }

    public ObjectNode attributeValueAsJson(String attribute) {
        final ObjectNode attrs = this.attributes();
        if (attrs.has(attribute)) {
            try {
                return (ObjectNode)objectMapper.readTree(attrs.get(attribute).asText());
            } catch (JsonProcessingException e) {
                log.atError().setCause(e).setMessage("Parsing of the locatorJSON has failed").log();
            }
        }

        return null;
    }

    public void addReference(Reference reference) {
        this.references.add(reference);
        json.withArray("references").add(reference.toJson());
    }

    private Semver determineExportedVersion() {
        String exportedVersion = json.has("coreMigrationVersion") ? json.get("coreMigrationVersion").asText(): null;

        String migrationVersion = json.has("migrationVersion") ? json.get("migrationVersion").get(objectType).asText() : null;

        if (migrationVersion != null) {
            return Semver.parse(migrationVersion);
        } else if (exportedVersion != null) {
            return Semver.parse(exportedVersion);
        } else {
            log.warn(
                "Object id {} with type {} does not have a migration version or core migration version.",
                id,
                objectType
            );
            return Semver.parse("0.0.0");
        }
    }


    public void updateVersion(String version) {
        this.version = Semver.parse(version);
        this.json.set("migrationVersion", JsonNodeFactory.instance.objectNode().put(objectType, version));
    }

    public void clearMigrationVersion() {
        this.json.remove("migrationVersion");
    }

    public ObjectNode json() {
        return json;
    }

    public String jsonAsString() {
        try {
            return objectMapper.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public void stringifyAttribute(String attribute, JsonNode visState) {
        final ObjectNode attributes = this.attributes();

        try {
            attributes.put(attribute, objectMapper.writeValueAsString(visState));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public Reference findReference(String refName) {
        return this.references.stream().filter(ref -> refName.equals(ref.getName())).findFirst().orElse(null);
    }
}
