package org.opensearch.migrations;

import com.beust.jcommander.IStringConverter;

public class VersionConverter implements IStringConverter<Version> {
    public Version convert(String value) {
        return Version.fromString(value);
    }
}
