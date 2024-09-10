package org.opensearch.migrations.dashboards.converter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.opensearch.migrations.dashboards.savedobjects.SavedObject;

import lombok.extern.slf4j.Slf4j;
import org.semver4j.Semver;

@Slf4j
public abstract class SavedObjectConverter<T extends SavedObject> {

    protected final Semver compatibility;
    protected boolean supported = true;
    protected DynamicMapping dynamic = DynamicMapping.FALSE;
    protected List<String> allowedAttributes = null;
    protected final SortedMap<Semver, Consumer<T>> migrations = new java.util.TreeMap<>(Comparator.reverseOrder());

    protected List<SavedObject> newSavedObjects = new ArrayList<>();

    public SavedObjectConverter() {
        this(Semver.parse("8.13.0"));
    }

    public SavedObjectConverter(Semver compatibility) {
        this.compatibility = compatibility;
    }

    protected T convertInner(T savedObject) {
        return savedObject;
    }

    protected void addMigration(String version, Consumer<T> converterFunction) {
        migrations.put(new Semver(version), converterFunction);
    }

    public SavedObject convert(T savedObject) {

        if (savedObject.getVersion().compareTo(this.compatibility) > 0) {
            log.warn(
                    "Object id {} with type {} core version {} is newer than the converter supported version {}. This might result in unexpected behavior.",
                    savedObject.getId(),
                    savedObject.getObjectType(),
                    savedObject.getVersion(),
                    this.compatibility
            );
        }

        if (!this.migrations.isEmpty()) {
            for (Map.Entry<Semver, Consumer<T>> entry : this.migrations.entrySet()) {
                log.debug("Version converter: {}", entry.getKey());
                if (savedObject.getVersion().isGreaterThan(entry.getKey())) {
                    entry.getValue().accept(savedObject);
                    
                    log.debug("Applied converter: {}", entry.getKey());

                    if (!entry.getKey().isEqualTo("0.0.0")) {
                        savedObject.updateVersion(entry.getKey().getVersion());
                    }
                }
            }
        }

        if (this.dynamic == DynamicMapping.STRICT) {
            this.removeUnsupportedAttributes(savedObject);
        }

        return savedObject;
    }

    private void removeUnsupportedAttributes(T savedObject) {
        final ObjectNode attributes = savedObject.attributes();
        if (attributes == null) {
            return;
        }

        if (this.allowedAttributes == null) {
            return;
        }

        final Iterator<Map.Entry<String, JsonNode>> fields = attributes.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String fieldName = field.getKey();

            if (!this.allowedAttributes.contains(fieldName)) {
                log.info(
                        "Object id {} with type {} has an unsupported attribute {}. This attribute will be removed.",
                        savedObject.getId(),
                        savedObject.getObjectType(),
                        fieldName
                );
                fields.remove();
            }
        }
    }

    protected void backportNothing(SavedObject savedObject) {
        // do nothing
    }

    public enum DynamicMapping {
        TRUE("true"),
        FALSE("false"),
        STRICT("strict");
    
        private String value;
    
        DynamicMapping(String value) {
            this.value = value;
        }
    
        public String getValue() {
            return value;
        }
    }
}
