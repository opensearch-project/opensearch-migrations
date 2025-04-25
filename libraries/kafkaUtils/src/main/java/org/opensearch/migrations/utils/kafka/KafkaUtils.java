package org.opensearch.migrations.utils.kafka;

import com.beust.jcommander.JCommander;
import org.opensearch.migrations.trafficcapture.proxyserver.CaptureProxy;

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
        public CaptureProxy.KafkaParameters kafkaParameters = new CaptureProxy.KafkaParameters();
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
        var kafkaOps = new KafkaOperations();
        kafkaOps.loadStreamsToKafkaFromCompressedFile(
                params.inputFile,
                params.kafkaParameters.kafkaPropertiesFile,
                params.kafkaParameters.kafkaConnection,
                params.kafkaParameters.kafkaClientId,
                params.kafkaParameters.mskAuthEnabled
        );
//        kafkaOps.loadStreamsToKafkaFromCompressedFile(
//                "/Volumes/workplace/opensearch/lewijacn-migrations/opensearch-migrations/libraries/kafkaUtils" +
//                        "/src/main/java/org/opensearch/migrations/utils/kafka/" +
//                        "kafka_export_from_migration_console_1745351831.proto.gz",
//                null,
//                "localhost:9092",
//                "TEST_ID",
//                false);
    }
}
