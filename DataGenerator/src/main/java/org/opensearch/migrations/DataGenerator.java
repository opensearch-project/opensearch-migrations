package org.opensearch.migrations;

import java.text.NumberFormat;

import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.OpenSearchClientFactory;
import org.opensearch.migrations.data.WorkloadGenerator;
import org.opensearch.migrations.utils.ProcessHelpers;

import com.beust.jcommander.JCommander;
import lombok.extern.slf4j.Slf4j;


/** Command line tool to generate data on a search cluster */
@Slf4j
public class DataGenerator {

    public static void main(String[] args) {
        var workerId = ProcessHelpers.getNodeInstanceName();
        log.info("Starting DataGenerator with workerId =" + workerId);

        var arguments = new DataGeneratorArgs();
        var jCommander = JCommander.newBuilder()
            .addObject(arguments)
            .build();
        jCommander.parse(args);

        if (arguments.help) {
            jCommander.usage();
            return;
        }

        var dataGenerator = new DataGenerator(); 
        dataGenerator.run(arguments);
    }

    public void run(DataGeneratorArgs arguments) {
        var connectionContext = arguments.targetArgs.toConnectionContext();
        var clientFactory = new OpenSearchClientFactory(null);
        var client = clientFactory.get(connectionContext);

        var startTimeMillis = System.currentTimeMillis();
        var workloadGenerator = new WorkloadGenerator(client);
        workloadGenerator.generate(arguments.workloadOptions);
        var generateTimeMillis = System.currentTimeMillis() - startTimeMillis;

        log.info("Generation complete, took {}ms", formatMillis(generateTimeMillis));
    }

    private String formatMillis(long millis) {
        var numberFormat = NumberFormat.getInstance();
        numberFormat.setMinimumFractionDigits(2);
        numberFormat.setMaximumFractionDigits(2);
        return numberFormat.format(millis);
    }
}
