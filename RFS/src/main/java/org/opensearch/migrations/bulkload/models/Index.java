package org.opensearch.migrations.bulkload.models;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(force = true, access = AccessLevel.PRIVATE) // For Jackson
public class Index extends MigrationItem {
    public static final String TYPE_NAME = "index";
    public Index(String name, ObjectNode body) {
        super(TYPE_NAME, name, body);
    }
}
