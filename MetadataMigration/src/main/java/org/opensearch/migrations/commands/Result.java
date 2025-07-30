package org.opensearch.migrations.commands;

/** All shared cli result information */
public interface Result extends JsonOutput {
    int getExitCode();
    String getErrorMessage();
    /** Render this result as a string for displaying on the command line  */
    String asCliOutput();
}
