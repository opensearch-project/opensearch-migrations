package org.opensearch.migrations.commands;

import org.apache.logging.log4j.util.Strings;

import org.opensearch.migrations.cli.Clusters;
import org.opensearch.migrations.cli.Items;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class MigrateResult implements Result {
    private final Clusters clusters;
    private final Items items;
    private final String errorMessage;
    private final int exitCode;

    public String toString() {
        var sb = new StringBuilder();
        if (getClusters() != null) {
            sb.append(getClusters() + System.lineSeparator());
        }
        if (getItems() != null) {
            sb.append(getItems() + System.lineSeparator());
        }
        sb.append("Results:" + System.lineSeparator());
        if (Strings.isNotBlank(getErrorMessage())) {
            sb.append("   Issue(s) detected" + System.lineSeparator());
            sb.append("Issues:" + System.lineSeparator());
            sb.append("   " + getErrorMessage() + System.lineSeparator());
        } else {
            sb.append("   " + getExitCode() + " issue(s) detected" + System.lineSeparator());
        }
        return sb.toString();
    }
}
