package org.opensearch.migrations;

import org.opensearch.migrations.commands.Configure;
import org.opensearch.migrations.commands.Evaluate;
import org.opensearch.migrations.commands.Migrate;
import org.opensearch.migrations.metadata.tracing.RootMetadataMigrationContext;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;

import com.beust.jcommander.JCommander;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MetadataMigration {

    public static void main(String[] args) throws Exception {
        var arguments = new MetadataArgs();
        var jCommander = JCommander.newBuilder().addObject(arguments).build();
        jCommander.parse(args);

        if (arguments.help) {
            jCommander.usage();
            return;
        }

        var context = new RootMetadataMigrationContext(
            RootOtelContext.initializeOpenTelemetryWithCollectorOrAsNoop(arguments.otelCollectorEndpoint, "metadata"),
            new CompositeContextTracker(new ActiveContextTracker(), new ActiveContextTrackerByActivityType())
        );

        var meta = new MetadataMigration(arguments);

        log.info("Starting Metadata Migration");

        var result = meta.migrate().execute(context);

        log.info(result.toString());
        System.exit(result.getExitCode());
    }

    private final MetadataArgs arguments;

    public MetadataMigration(MetadataArgs arguments) {
        this.arguments = arguments;
    }

    public Configure configure() {
        return new Configure();
    }

    public Evaluate evaluate() {
        return new Evaluate();
    }

    public Migrate migrate() {
        return new Migrate(arguments);
    }
}
