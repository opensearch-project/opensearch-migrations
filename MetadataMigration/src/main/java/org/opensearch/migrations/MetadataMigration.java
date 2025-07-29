package org.opensearch.migrations;

import java.nio.file.Path;
import java.util.Optional;

import org.opensearch.migrations.arguments.ArgLogUtils;
import org.opensearch.migrations.arguments.ArgNameConstants;
import org.opensearch.migrations.commands.*;
import org.opensearch.migrations.metadata.tracing.RootMetadataMigrationContext;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;
import org.opensearch.migrations.utils.ProcessHelpers;

import com.beust.jcommander.JCommander;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;

@Slf4j
public class MetadataMigration {

    public static void main(String[] args) throws Exception {
        System.err.println("Starting program with: " + String.join(" ", ArgLogUtils.getRedactedArgs(
                args,
                ArgNameConstants.joinLists(ArgNameConstants.CENSORED_SOURCE_ARGS, ArgNameConstants.CENSORED_TARGET_ARGS)
        )));
        var metadataArgs = new MetadataArgs();
        var migrateArgs = new MigrateArgs();
        var evaluateArgs = new EvaluateArgs();
        var jCommander = JCommander.newBuilder()
            .addObject(metadataArgs)
            .addCommand(migrateArgs)
            .addCommand(evaluateArgs)
            .build();
        jCommander.parse(args);
        EnvArgs.injectFromEnv(migrateArgs);
        EnvArgs.injectFromEnv(evaluateArgs);

        var context = new RootMetadataMigrationContext(
            RootOtelContext.initializeOpenTelemetryWithCollectorOrAsNoop(metadataArgs.otelCollectorEndpoint, "metadata",
                ProcessHelpers.getNodeInstanceName()),
            new CompositeContextTracker(new ActiveContextTracker(), new ActiveContextTrackerByActivityType())
        );

        var meta = new MetadataMigration();

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

        reportLogPath();
        reportTransformationPath();

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
            sb.append("  ").append(parameter.getNames());
            sb.append("    ").append(parameter.getDescription());
        }

        sb.append("Commands:");
        for (var command : commander.getCommands().entrySet()) {
            sb.append("  ").append(command.getKey());
        }
        sb.append("\nUse --help with a specific command for more information.");
        log.info(sb.toString());
    }

    private static void printCommandUsage(JCommander jCommander) {
        var sb = new StringBuilder();
        jCommander.getUsageFormatter().usage(jCommander.getParsedCommand(), sb);
        log.info(sb.toString());
    }

    private static void reportLogPath() {
        try {
            var loggingContext = (LoggerContext) LogManager.getContext(false);
            var loggingConfig = loggingContext.getConfiguration();
            // Log appender name is in from the MetadataMigration/src/main/resources/log4j2.properties
            var metadataLogAppender = (FileAppender) loggingConfig.getAppender("MetadataRun");
            if (metadataLogAppender != null) {
                var logFilePath = Path.of(metadataLogAppender.getFileName()).normalize();
                log.atInfo()
                    .setMessage("Consult {} to see detailed logs for this run")
                    .addArgument(logFilePath.toAbsolutePath())
                    .log();
            }
        } catch (Exception e) {
            // Ignore any exceptions if we are unable to get the log configuration
        }
    }

    private static void reportTransformationPath() {
        try {
            var loggingContext = (LoggerContext) LogManager.getContext(false);
            var loggingConfig = loggingContext.getConfiguration();
            var metadataLogAppender = (FileAppender) loggingConfig.getAppender(MetadataTransformationRegistry.TRANSFORM_LOGGER_NAME);
            if (metadataLogAppender != null) {
                var logFilePath = Path.of(metadataLogAppender.getFileName()).normalize();
                log.atInfo()
                        .setMessage("See transformations applied for this run at {}")
                        .addArgument(logFilePath.toAbsolutePath())
                        .log();
            }
        } catch (Exception e) {
            // Ignore any exceptions if we are unable to get the log configuration
        }
    }

}
