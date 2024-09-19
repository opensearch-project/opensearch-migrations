package org.opensearch.migrations.commands;

/** All shared cli result information */
public interface Result {
    int getExitCode();
    String getErrorMessage();
    /** Render this result as a string for displaying on the command line  */
    String asCliOutput();
}
