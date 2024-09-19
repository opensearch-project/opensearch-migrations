package org.opensearch.migrations.cli;

import lombok.experimental.UtilityClass;

/** Shared formatting for command line interface components */
@UtilityClass
public class Format {

    private static final String INDENT = "   ";

    /** Indents to a given level for printing to the console */
    public static String indentToLevel(final int level) {
        return INDENT.repeat(level);
    }
}
