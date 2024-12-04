package org.opensearch.migrations.bulkload.models;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(force = true, access = AccessLevel.PROTECTED) // For Jackson
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Index.class, name = Index.TYPE),
        @JsonSubTypes.Type(value = LegacyTemplate.class, name = LegacyTemplate.TYPE),
        @JsonSubTypes.Type(value = IndexTemplate.class, name = IndexTemplate.TYPE),
        @JsonSubTypes.Type(value = ComponentTemplate.class, name = ComponentTemplate.TYPE)
})
public abstract class MigrationItem {
    public final String type;
    public final String name;
    public final ObjectNode body;

    public MigrationItem(final String type, final String name, final ObjectNode body) {
        this.type = type;
        this.name = name;
        this.body = body;
    }
}
