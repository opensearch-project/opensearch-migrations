package org.opensearch.migrations.cli;

import com.beust.jcommander.IStringConverter;

/**
 * Defines the supported output formats for the metadata migration tool.
 */
public enum OutputFormat {
    HUMAN_READABLE,
    JSON;

    public static class OutputFormatConverter implements IStringConverter<OutputFormat> {
        @Override
        public OutputFormat convert(String value) {
            try {
                return OutputFormat.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid output format: " + value + ".");
            }
        }
    }
}
