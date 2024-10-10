package org.opensearch.migrations;

import java.util.Optional;

import org.opensearch.migrations.commands.Configure;
import org.opensearch.migrations.commands.Evaluate;
import org.opensearch.migrations.commands.EvaluateArgs;
import org.opensearch.migrations.commands.Migrate;
import org.opensearch.migrations.commands.MigrateArgs;
import org.opensearch.migrations.commands.Result;
import org.opensearch.migrations.metadata.tracing.RootMetadataMigrationContext;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;
import org.opensearch.migrations.utils.ProcessHelpers;
import org.opensearch.migrations.config.MigrationConfig;

import com.beust.jcommander.JCommander;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MetadataMigration {

    public static void main(String[] args) throws Exception {

        var metadataArgs = new MetadataArgs();
        var migrateArgs = new MigrateArgs();
        // Note; need to handle these effectively duplicated parsed args 
        var evaluateArgs = new EvaluateArgs();

        // Load from the command line first
        JCommander.newBuilder()
            .addObject(metadataArgs)
            .addCommand(migrateArgs)
            .addCommand(evaluateArgs)
            .build()
            .parse(args);

        // Then override with settings
        var config = getConfig(args);
        if (config != null) {
            metadataArgs.otelCollectorEndpoint = config.metadata_migration.otel_endpoint;

            // Note; we've got some serious null ref risk in this block of code, will need to use a lot of optionals.
            migrateArgs.dataFilterArgs.indexAllowlist = config.metadata_migration.index_allowlist;
            migrateArgs.dataFilterArgs.indexTemplateAllowlist = config.metadata_migration.index_template_allowlist;
            migrateArgs.dataFilterArgs.componentTemplateAllowlist = config.metadata_migration.component_template_allowlist;

            migrateArgs.fileSystemRepoPath = config.snapshot.fs.repo_path;
            migrateArgs.snapshotName = config.snapshot.snapshot_name;
            migrateArgs.s3LocalDirPath = config.metadata_migration.local_dir;
            migrateArgs.s3Region = config.snapshot.s3.aws_region;
            migrateArgs.s3RepoUri = config.snapshot.s3.repo_uri;

            migrateArgs.sourceArgs.host = config.source_cluster.endpoint;
            migrateArgs.sourceArgs.username = config.source_cluster.basic_auth.username;
            migrateArgs.sourceArgs.password = config.source_cluster.basic_auth.password;
            migrateArgs.sourceArgs.awsRegion = config.source_cluster.sigv4.region;
            migrateArgs.sourceArgs.awsServiceSigningName = config.source_cluster.sigv4.service;
            migrateArgs.sourceArgs.insecure = config.source_cluster.allow_insecure;

            // Need to special case indirect values such as AWS Secrets
            if (config.source_cluster.basic_auth.password_from_secret_arn != null) {
                migrateArgs.sourceArgs.password = ""; // Load this from AWS and insert into this arg + log a message
            }

            migrateArgs.targetArgs.host = config.target_cluster.endpoint;
            migrateArgs.targetArgs.username = config.target_cluster.basic_auth.username;
            migrateArgs.targetArgs.password = config.target_cluster.basic_auth.password;
            migrateArgs.targetArgs.awsRegion = config.target_cluster.sigv4.region;
            migrateArgs.targetArgs.awsServiceSigningName = config.target_cluster.sigv4.service;
            migrateArgs.targetArgs.insecure = config.target_cluster.allow_insecure;

            // Need to special case indirect values such as AWS Secrets
            if (config.target_cluster.basic_auth.password != null) {
                migrateArgs.targetArgs.password = ""; // Load this from AWS and insert into this arg + log a message
            }

            migrateArgs.minNumberOfReplicas = config.metadata_migration.min_replicas;
        }

        var context = new RootMetadataMigrationContext(
            RootOtelContext.initializeOpenTelemetryWithCollectorOrAsNoop(metadataArgs.otelCollectorEndpoint, "metadata",
                ProcessHelpers.getNodeInstanceName()),
            new CompositeContextTracker(new ActiveContextTracker(), new ActiveContextTrackerByActivityType())
        );

        var meta = new MetadataMigration();

        // TODO: Add back arg printing after not consuming plaintext password MIGRATIONS-1915

        if (metadataArgs.help || jCommander.getParsedCommand() == null) {
            printTopLevelHelp(jCommander);
            return;
        }

        if (migrateArgs.help || evaluateArgs.help) {
            printCommandUsage(jCommander);
            return;
        }

        var command = Optional.ofNullable(jCommander.getParsedCommand())
            .map(MetadataCommands::fromString)
            .orElse(MetadataCommands.MIGRATE);
        Result result;
        switch (command) {
            default:
            case MIGRATE:
                if (migrateArgs.help) {
                    printCommandUsage(jCommander);
                    return;
                }

                log.info("Starting Metadata Migration");
                result = meta.migrate(migrateArgs).execute(context);
                break;
            case EVALUATE:
                if (evaluateArgs.help) {
                    printCommandUsage(jCommander);
                    return;
                }

                log.info("Starting Metadata Evaluation");
                result = meta.evaluate(evaluateArgs).execute(context);
                break;
        }
        log.atInfo().setMessage("{}").addArgument(result::asCliOutput).log();
        System.exit(result.getExitCode());
    }

    public Configure configure() {
        return new Configure();
    }

    public Evaluate evaluate(MigrateOrEvaluateArgs arguments) {
        return new Evaluate(arguments);
    }

    public Migrate migrate(MigrateOrEvaluateArgs arguments) {
        return new Migrate(arguments);
    }

    private static void printTopLevelHelp(JCommander commander) {
        var sb = new StringBuilder();
        sb.append("Usage: [options] [command] [commandOptions]");
        sb.append("Options:");
        for (var parameter : commander.getParameters()) {
            sb.append("  " + parameter.getNames());
            sb.append("    " + parameter.getDescription());
        }

        sb.append("Commands:");
        for (var command : commander.getCommands().entrySet()) {
            sb.append("  " + command.getKey());
        }
        sb.append("\nUse --help with a specific command for more information.");
        log.info(sb.toString());
    }

    private static void printCommandUsage(JCommander jCommander) {
        var sb = new StringBuilder();
        jCommander.getUsageFormatter().usage(jCommander.getParsedCommand(), sb);
        log.info(sb.toString());
    }

    private static MigrationConfig getConfig(String[] args) {
        var metadataArgs = new MetadataArgs();

        JCommander.newBuilder()
            .addObject(metadataArgs)
            .acceptUnknownOptions(true)
            .build()
            .parse(args);

        if (metadataArgs.configFile != null) {
            try {
                return MigrationConfig.loadFrom(metadataArgs.configFile);
            } catch (Exception e) {
                log.warn("Unable to load from config file, falling back to command line arguments.");
            }
        }
        return null;

    }
}
