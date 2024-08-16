package org.opensearch.migrations.dashboards;

import java.io.*;
import java.util.Scanner;

import com.google.gson.Gson;

import org.opensearch.migrations.dashboards.model.Dashboard;
import org.opensearch.migrations.dashboards.util.Stats;

import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "Dashboard Sanitizer", version = "0.1", mixinStandardHelpOptions = true)
@Slf4j
public class Sanitizer implements Runnable{

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
        try {
            Scanner scanner = new Scanner(new BufferedInputStream(new FileInputStream(sourceFile)));
            BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile));
            Stats stats = sanitizeDashboardsFromFile(scanner, writer);
            System.out.printf("%s file is sanitized and output available at %s%n", sourceFile, outputFile);
            stats.printStats();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        CommandLine cmd = new CommandLine(new Sanitizer());
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
    }

    public static Stats sanitizeDashboardsFromFile(Scanner source, BufferedWriter writer) throws IOException {

        Gson gson = new Gson();
        Stats counter = new Stats();

        while (source.hasNextLine()) {
            String line = source.nextLine();
            Dashboard dashboardObject = gson.fromJson(line, Dashboard.class);
            // if dashboard id is null, it could be summary line, skip the line
            if (dashboardObject.getId() == null) {
                counter.registerSkipped(dashboardObject);
                continue;
            } else if (!dashboardObject.isCompatibleType()) {
                counter.registerSkipped(dashboardObject);
                continue;
            }

            dashboardObject.makeCompatibleToOS();
            writer.write(gson.toJson(dashboardObject));
            writer.newLine();
            writer.flush();
            counter.registerProcessed(dashboardObject);
        }
        return counter;
    }
}
