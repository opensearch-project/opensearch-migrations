package org.opensearch.migrations.bulkload.models;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(force = true, access = AccessLevel.PRIVATE) // For Jackson
public class Index extends MigrationItem {
    public final static String TYPE = "index";
    public Index(String name, ObjectNode body) {
        super(TYPE, name, body);
    }
}
