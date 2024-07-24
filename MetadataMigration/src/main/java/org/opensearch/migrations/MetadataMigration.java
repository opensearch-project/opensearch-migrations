package org.opensearch.migrations;

import org.opensearch.migrations.commands.Configure;
import org.opensearch.migrations.commands.Evaluate;
import org.opensearch.migrations.commands.Migrate;

public class MetadataMigration {

    public Configure configure() {
        return new Configure();
    }

    public Evaluate evaluate() {
        return new Evaluate();
    }

    public Migrate migrate() {
        return new Migrate();
    }
}
