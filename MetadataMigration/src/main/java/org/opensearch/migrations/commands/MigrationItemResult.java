package org.opensearch.migrations.commands;

import org.apache.logging.log4j.util.Strings;

import org.opensearch.migrations.cli.Clusters;
import org.opensearch.migrations.cli.Format;
import org.opensearch.migrations.cli.Items;

/** All shared cli result information */
public interface MigrationItemResult extends Result {
    Clusters getClusters();
    Items getItems();

    default String asCliOutput() {
        var sb = new StringBuilder();
        if (getClusters() != null) {
            sb.append(getClusters().asCliOutput() + System.lineSeparator());
        }
        if (getItems() != null) {
            sb.append(getItems().asCliOutput() + System.lineSeparator());
        }
        sb.append("Results:" + System.lineSeparator());
        if (Strings.isNotBlank(getErrorMessage())) {
            sb.append(Format.indentToLevel(1) + "Issue(s) detected" + System.lineSeparator());
            sb.append("Issues:" + System.lineSeparator());
            sb.append(Format.indentToLevel(1) + getErrorMessage() + System.lineSeparator());
        } else {
            sb.append(Format.indentToLevel(1) + getExitCode() + " issue(s) detected" + System.lineSeparator());
        }
        return sb.toString();
    }
}
