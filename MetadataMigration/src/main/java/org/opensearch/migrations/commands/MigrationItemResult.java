package org.opensearch.migrations.commands;

import java.util.ArrayList;
import java.util.List;

import org.opensearch.migrations.cli.Clusters;
import org.opensearch.migrations.cli.Format;
import org.opensearch.migrations.cli.Items;

import org.apache.logging.log4j.util.Strings;

/** All shared cli result information */
public interface MigrationItemResult extends Result {
    Clusters getClusters();
    Items getItems();

    default List<String> collectErrors() {
        var errors = new ArrayList<String>();
        if (getClusters() == null || getClusters().getSource() == null) {
            errors.add("No source was defined");
        }
        if (getClusters() == null || getClusters().getTarget() == null) {
            errors.add("No target was defined");
        }

        if (getItems() != null) {
            errors.addAll(getItems().getAllErrors());
        }
        return errors;
    }

    default String asCliOutput() {
        var sb = new StringBuilder();
        if (getClusters() != null) {
            sb.append(getClusters().asCliOutput() + System.lineSeparator());
        }
        if (getItems() != null) {
            sb.append(getItems().asCliOutput() + System.lineSeparator());
        }
        sb.append("Results:" + System.lineSeparator());
        var innerErrors = collectErrors();
        if (Strings.isNotBlank(getErrorMessage()) || !innerErrors.isEmpty()) {
            sb.append(Format.indentToLevel(1) + "Issue(s) detected" + System.lineSeparator());
            sb.append("Issues:" + System.lineSeparator());
            if (Strings.isNotBlank(getErrorMessage())) {
                sb.append(Format.indentToLevel(1) + getErrorMessage() + System.lineSeparator());
            }
            if (!innerErrors.isEmpty()) {
                innerErrors.forEach(err -> sb.append(Format.indentToLevel(1) + err + System.lineSeparator()));
            }
        } else {
            sb.append(Format.indentToLevel(1) + getExitCode() + " issue(s) detected" + System.lineSeparator());
        }
        return sb.toString();
    }
}
