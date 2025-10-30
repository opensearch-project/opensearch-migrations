package org.opensearch.migrations;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.arguments.ArgLogUtils;
import org.opensearch.migrations.arguments.ArgNameConstants;
import org.opensearch.migrations.cli.OutputFormat;
import org.opensearch.migrations.commands.*;
import org.opensearch.migrations.jcommander.EnvVarParameterPuller;
import org.opensearch.migrations.jcommander.JsonCommandLineParser;
import org.opensearch.migrations.metadata.tracing.RootMetadataMigrationContext;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;
import org.opensearch.migrations.utils.ProcessHelpers;

import com.beust.jcommander.JCommander;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;

@Slf4j
public class MetadataMigration {

    public static final String ENV_PREFIX = "METADATA_MIGRATE_";

    public static void main(String[] args) {
        new MetadataMigration().run(args);
    }

    private AtomicReference<OutputFormat> outputFormat = new AtomicReference<>();

    protected void run(String[] args) {
        System.err.println("Starting program with: " + String.join(" ", ArgLogUtils.getRedactedArgs(
                args,
                ArgNameConstants.joinLists(ArgNameConstants.CENSORED_SOURCE_ARGS, ArgNameConstants.CENSORED_TARGET_ARGS)
        )));
        var metadataArgs = EnvVarParameterPuller.injectFromEnv(new MetadataArgs(), ENV_PREFIX);
        var migrateArgs  = EnvVarParameterPuller.injectFromEnv(new MigrateArgs(),  ENV_PREFIX);
        var evaluateArgs = EnvVarParameterPuller.injectFromEnv(new EvaluateArgs(), ENV_PREFIX);
        var argsParser = JsonCommandLineParser.newBuilder()
            .addObject(metadataArgs)
            .addCommand(migrateArgs)
            .addCommand(evaluateArgs)
            .build();
        argsParser.parse(args);

        if (migrateArgs.outputFormat == OutputFormat.JSON || evaluateArgs.outputFormat == OutputFormat.JSON) {
            outputFormat.set(OutputFormat.JSON);
        } else {
            outputFormat.set(OutputFormat.HUMAN_READABLE);
        }

        if (metadataArgs.help || argsParser.getParsedCommand() == null) {
            printTopLevelHelp(argsParser.getJCommander());
            return;
        }

        if (migrateArgs.help || evaluateArgs.help) {
            printCommandUsage(argsParser.getJCommander());
            return;
        }

        var result = runCommand(argsParser.getJCommander(), metadataArgs, migrateArgs, evaluateArgs);

        // Output format determines which version is printed to the user
        writeOutput(result.asCliOutput());
        writeOutput(result.asJsonOutput());
        reportLogPath();
        reportTransformationPath();

        exitWithCode(result.getExitCode());
    }

    private Result runCommand(JCommander jCommander, MetadataArgs metadataArgs, MigrateArgs migrateArgs, EvaluateArgs evaluateArgs) {
        var context = new RootMetadataMigrationContext(
            RootOtelContext.initializeOpenTelemetryWithCollectorOrAsNoop(metadataArgs.otelCollectorEndpoint, "metadata",
                ProcessHelpers.getNodeInstanceName()),
            new CompositeContextTracker(new ActiveContextTracker(), new ActiveContextTrackerByActivityType())
        );

        var command = Optional.ofNullable(jCommander.getParsedCommand())
            .map(MetadataCommands::fromString)
            .orElse(MetadataCommands.MIGRATE);

        switch (command) {
            default:
            case MIGRATE:
                if (migrateArgs.help) {
                    printCommandUsage(jCommander);
                    exitWithCode(-1);
                }

                writeOutput("Starting Metadata Migration");
                return migrate(migrateArgs).execute(context);
            case EVALUATE:
                if (evaluateArgs.help) {
                    printCommandUsage(jCommander);
                    exitWithCode(-1);
                }

                writeOutput("Starting Metadata Evaluation");
                return evaluate(evaluateArgs).execute(context);
        }
    }

    protected void exitWithCode(int code) {
        System.exit(code);
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

    protected void writeOutput(String humanReadableOutput) {
        if (outputFormat.get() == OutputFormat.HUMAN_READABLE) {
            log.atInfo().setMessage("{}").addArgument(humanReadableOutput).log();
        }
    }

    protected void writeOutput(JsonNode output) {
        if (outputFormat.get() == OutputFormat.JSON) {
            log.atInfo().setMessage("{}").addArgument(output::toPrettyString).log();
        }
    }

    private void printTopLevelHelp(JCommander commander) {
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
        var usage = sb.toString();
        writeOutput(usage);
    }

    private void printCommandUsage(JCommander jCommander) {
        var sb = new StringBuilder();
        jCommander.getUsageFormatter().usage(jCommander.getParsedCommand(), sb);
        writeOutput(sb.toString());
    }

    private void reportLogPath() {
        try {
            var loggingContext = (LoggerContext) LogManager.getContext(false);
            var loggingConfig = loggingContext.getConfiguration();
            // Log appender name is in from the MetadataMigration/src/main/resources/log4j2.properties
            var metadataLogAppender = (FileAppender) loggingConfig.getAppender("MetadataRun");
            if (metadataLogAppender != null) {
                var logFilePath = Path.of(metadataLogAppender.getFileName()).normalize();
                writeOutput("Consult " + logFilePath.toAbsolutePath() + " to see detailed logs for this run");
            }
        } catch (Exception e) {
            // Ignore any exceptions if we are unable to get the log configuration
        }
    }

    private void reportTransformationPath() {
        try {
            var loggingContext = (LoggerContext) LogManager.getContext(false);
            var loggingConfig = loggingContext.getConfiguration();
            var metadataLogAppender = (FileAppender) loggingConfig.getAppender(MetadataTransformationRegistry.TRANSFORM_LOGGER_NAME);
            if (metadataLogAppender != null) {
                var logFilePath = Path.of(metadataLogAppender.getFileName()).normalize();
                writeOutput("See transformations applied for this run at " + logFilePath.toAbsolutePath());
            }
        } catch (Exception e) {
            // Ignore any exceptions if we are unable to get the log configuration
        }
    }

}
