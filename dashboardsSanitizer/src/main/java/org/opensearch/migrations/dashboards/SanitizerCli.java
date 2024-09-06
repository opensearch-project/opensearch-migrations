package org.opensearch.migrations.dashboards;

import java.io.*;
import java.util.Optional;
import java.util.Scanner;

import org.opensearch.migrations.dashboards.util.Stats;

import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "Dashboard Sanitizer", version = "0.1", mixinStandardHelpOptions = true)
@Slf4j
public class SanitizerCli implements Runnable{

    @CommandLine.Option(names = {"-V", "--version"}, versionHelp = true, description = "display version info")
    boolean versionInfoRequested;

    @CommandLine.Option(names = {"?", "-h", "--help"}, usageHelp = true, description = "display this help message")
    boolean usageHelpRequested;

    @CommandLine.Option(names = {"-s", "--source"}, required = true, description = "The Elastic dashboard object file in ndjson.")
    private String sourceFile;

    @CommandLine.Option(names = {"-o", "--output"}, required = true, description = "The sanitized OpenSearch dashboard object file in ndjson.", defaultValue = "os-dashboards.ndjson")
    private String outputFile;

    @Override
    public void run() {
        //check for sourceFile, if empty, print usage and return
        if (sourceFile.isEmpty()) {
            CommandLine.usage(this, System.out);
        }
        try (Scanner scanner = new Scanner(new BufferedInputStream(new FileInputStream(sourceFile)));
            BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            
            Stats stats = sanitizeDashboardsFromFile(scanner, writer);
            log.info("Input file {} is sanitized and output available at %", sourceFile, outputFile);
            log.info(stats.printStats());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        CommandLine cmd = new CommandLine(new SanitizerCli());
        try {
            cmd.parseArgs(args);

            if (cmd.isUsageHelpRequested() ) {
                cmd.usage(System.out);
                return;
            } else if (cmd.isVersionHelpRequested()) {
                cmd.printVersionHelp(System.out);
                return;
            }
            int exitCode = cmd.execute(args);
            System.exit(exitCode);
        } catch (CommandLine.ParameterException e) {
            System.err.println(e.getMessage());
            cmd.usage(System.err);
            System.exit(1);
        }
        
    }

    public static Stats sanitizeDashboardsFromFile(Scanner source, BufferedWriter writer) throws IOException {
        Sanitizer sanitizer = Sanitizer.getInstance();

        while (source.hasNextLine()) {
            String line = source.nextLine();
            String sanitizedLine = sanitizer.sanitize(line);

            if (sanitizedLine == null) {
                continue;
            }
            writer.write(sanitizedLine);
            writer.newLine();
            writer.flush();
        }
        return sanitizer.getStats();
    }
}
