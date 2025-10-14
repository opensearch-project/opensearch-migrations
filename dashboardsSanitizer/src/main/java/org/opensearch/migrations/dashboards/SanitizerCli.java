package org.opensearch.migrations.dashboards;

import java.io.*;
import java.util.Scanner;

import org.opensearch.migrations.dashboards.util.Stats;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SanitizerCli implements Runnable {

    @Parameter(names = {"?", "-h", "--help"}, help = true, description = "display this help message")
    boolean usageHelpRequested;

    @Parameter(names = {"-s", "--source"}, required = true, description = "The Elastic dashboard object file in ndjson.")
    private String sourceFile;

    @Parameter(names = {"-o", "--output"}, required = false, description = "The sanitized OpenSearch dashboard object file in ndjson.")
    private String outputFile = "sanitized-dashboards.ndjson";

    @Override
    public void run() {
        //check for sourceFile, if empty, print usage and return
        try (Scanner scanner = new Scanner(new BufferedInputStream(new FileInputStream(sourceFile)));
            BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            
            Stats stats = sanitizeDashboardsFromFile(scanner, writer);
            log.atInfo().setMessage("Input file {} is sanitized and output available at {}").addArgument(sourceFile).addArgument(outputFile).log();
            log.atInfo().setMessage("{}").addArgument(stats.printStats()).log();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        SanitizerCli cli = new SanitizerCli();
        JCommander jCommander = JCommander.newBuilder().addObject(cli).build();

        try {
            jCommander.parse(args);
            
            if (cli.usageHelpRequested) {
                jCommander.usage();
                return;
            }
            
            cli.run();
        } catch (ParameterException e) {
            log.atError().setCause(e).setMessage("Parameter error").log();
            jCommander.usage();
            return;
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
