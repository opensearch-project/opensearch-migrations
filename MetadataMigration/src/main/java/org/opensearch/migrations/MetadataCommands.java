package org.opensearch.migrations;

/** The list of supported commands for the metadata tool */
public enum MetadataCommands {
    /** Migrates items from a source and recreates them on the target cluster */
    MIGRATE,

    /** Inspects items from a source to determine which can be placed on a target cluster */
    EVALUATE;

    public static MetadataCommands fromString(String s) {
        for (var command : values()) {
            if (command.name().equalsIgnoreCase(s)) {
                return command;
            }
        }
        throw new IllegalArgumentException("Unable to find matching command for text:" + s);
    }
}
