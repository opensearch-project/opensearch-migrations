package org.opensearch.migrations.utils.kafka;

import org.opensearch.migrations.trafficcapture.kafkaoffloader.KafkaConfig;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.ParametersDelegate;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class KafkaUtils {
    public static final String DEFAULT_TOPIC_NAME = "logging-traffic-topic";
    public static final int DEFAULT_BATCH_SIZE = 500;

    public static class Parameters {
        @Parameter(required = true,
                names = { "--inputFile", "input-file" },
                arity = 1,
                description = "The gzip compressed file containing Capture Proxy produced traffic streams.")
        public String inputFile;
        @Parameter(required = false,
                names = { "--topicName", "topic-name" },
                arity = 1,
                description = "The Kafka topic name to use.")
        public String topicName = DEFAULT_TOPIC_NAME;
        @Parameter(required = false,
                names = { "--batchSize", "batch-size" },
                arity = 1,
                description = "The number of records to batch when sending to Kafka.")
        public int batchSize = DEFAULT_BATCH_SIZE;
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
            log.error(e.getMessage());
            log.error("Got args: {}", String.join("; ", args));
            jCommander.usage();
            System.exit(2);
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        var params = parseArgs(args);
        var kafkaLoader = new KafkaLoader(
                params.kafkaParameters.kafkaPropertiesFile,
                params.kafkaParameters.kafkaConnection,
                params.kafkaParameters.kafkaClientId,
                params.kafkaParameters.mskAuthEnabled
        );
        kafkaLoader.loadRecordsToKafkaFromCompressedFile(
                params.inputFile,
                params.topicName,
                params.batchSize
        );
    }
}
