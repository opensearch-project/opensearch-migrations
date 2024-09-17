package org.opensearch.migrations.commands;

import org.opensearch.migrations.cli.Clusters;
import org.opensearch.migrations.cli.Items;

/** All shared cli result information */
public interface MigrationItemResult extends Result {
    Clusters getClusters();
    Items getItems();
}
