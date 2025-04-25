package org.opensearch.migrations.utils.kafka;

import org.opensearch.migrations.trafficcapture.kafkaoffloader.KafkaConfig;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.ParametersDelegate;


public class KafkaUtils {

    public static class Parameters {
        @Parameter(required = true,
                names = { "--inputFile", "input-file" },
                arity = 1,
                description = "The gzip compressed file containing Capture Proxy produced traffic streams.")
        public String inputFile;
        @ParametersDelegate
        public KafkaConfig.KafkaParameters kafkaParameters = new KafkaConfig.KafkaParameters();
    }

    static Parameters parseArgs(String[] args) {
        Parameters p = new Parameters();
        JCommander jCommander = new JCommander(p);
        try {
            jCommander.parse(args);
            if (p.kafkaParameters.kafkaConnection == null) {
                throw new ParameterException("Missing required parameter: --kafkaConnection");
            }
            return p;
        } catch (ParameterException e) {
            System.err.println(e.getMessage());
            System.err.println("Got args: " + String.join("; ", args));
            jCommander.usage();
            System.exit(2);
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        var params = parseArgs(args);
        var kafkaLoader = new KafkaLoader();
        kafkaLoader.loadRecordsToKafkaFromCompressedFile(
                params.inputFile,
                params.kafkaParameters.kafkaPropertiesFile,
                params.kafkaParameters.kafkaConnection,
                params.kafkaParameters.kafkaClientId,
                params.kafkaParameters.mskAuthEnabled
        );
    }
}
