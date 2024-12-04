package org.opensearch.migrations.bulkload.models;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(force = true, access = AccessLevel.PRIVATE) // For Jackson
public class LegacyTemplate extends MigrationItem {
    public final static String TYPE = "template";
    public LegacyTemplate(final String name, final ObjectNode body) {
        super(TYPE, name, body);
    }
}
