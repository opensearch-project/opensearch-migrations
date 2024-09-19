package org.opensearch.migrations;

/** The list of supported commands for the metadata tool */
public enum MetadataCommands {
    /** Migrates items from a source and recreates them on the target cluster */
    Migrate,

    /** Inspects items from a source to determine which can be placed on a target cluster */
    Evaluate;

    public static MetadataCommands fromString(String s) {
        for (var command : values()) {
            if (command.name().toLowerCase().equals(s.toLowerCase())) {
                return command;
            }
        }
        throw new IllegalArgumentException("Unable to find matching command for text:" + s);
    }
}
