package org.opensearch.migrations;

/** The list of supported commands for the metadata tool */
public enum MetadataCommands {
    /** Migrates items from a source and recreates them on the target cluster */
    MIGRATE,

    /** Inspects items from a source to determine which can be placed on a target cluster */
    EVALUATE,

    /** Checks list and read access to a configured snapshot repository */
    CHECK_REPOSITORY;

    public static MetadataCommands fromString(String s) {
        String normalized = s.replace('-', '_');
        for (var command : values()) {
            if (command.name().equalsIgnoreCase(normalized)) {
                return command;
            }
        }
        throw new IllegalArgumentException("Unable to find matching command for text:" + s);
    }
}
