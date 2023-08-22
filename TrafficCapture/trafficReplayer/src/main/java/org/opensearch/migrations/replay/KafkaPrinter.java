package org.opensearch.migrations.replay;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.protobuf.CodedOutputStream;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Caution: This is a utility tool for printing Kafka records and is intended to be used adhoc as needed for outputting
 * Kafka records, normally to the console or redirected to a file, and is not needed for normal functioning of the
 * Replayer. Some use cases where this tool is handy include:
 *
 * 1. Capturing a particular set of Kafka traffic records to a file which can then be repeatedly passed as an input param
 * to the Replayer for testing.
 * 2. Printing records for a topic with a different group id then is used normally to compare
 * 3. Clearing records for a topic with the same group id
 */
public class KafkaPrinter {
    private static final Logger log = LoggerFactory.getLogger(KafkaPrinter.class);
    public static final Duration CONSUMER_POLL_TIMEOUT = Duration.ofSeconds(1);

    static class Parameters {
        @Parameter(required = true,
            names = {"--kafka-traffic-brokers"},
            arity=1,
            description = "Comma-separated list of host and port pairs that are the addresses of the Kafka brokers to bootstrap with i.e. 'localhost:9092,localhost2:9092'")
        String kafkaTrafficBrokers;
        @Parameter(required = true,
            names = {"--kafka-traffic-topic"},
            arity=1,
            description = "Topic name used to pull messages from Kafka")
        String kafkaTrafficTopic;
        @Parameter(required = true,
            names = {"--kafka-traffic-group-id"},
            arity=1,
            description = "Consumer group id that is used when pulling messages from Kafka")
        String kafkaTrafficGroupId;
        @Parameter(required = false,
            names = {"--kafka-traffic-enable-msk-auth"},
            arity=0,
            description = "Enables SASL properties required for connecting to MSK with IAM auth")
        boolean kafkaTrafficEnableMSKAuth;
        @Parameter(required = false,
            names = {"--kafka-traffic-property-file"},
            arity=1,
            description = "File path for Kafka properties file to use for additional or overriden Kafka properties")
        String kafkaTrafficPropertyFile;
    }

    public static Parameters parseArgs(String[] args) {
        Parameters p = new Parameters();
        JCommander jCommander = new JCommander(p);
        try {
            jCommander.parse(args);
            return p;
        } catch (ParameterException e) {
            System.err.println(e.getMessage());
            System.err.println("Got args: "+ String.join("; ", args));
            jCommander.usage();
            throw e;
        }
    }

    public static void main(String[] args) {
        Parameters params;
        try {
            params = parseArgs(args);
        } catch (ParameterException e) {
            return;
        }

        String bootstrapServers = params.kafkaTrafficBrokers;
        String groupId = params.kafkaTrafficGroupId;
        String topic = params.kafkaTrafficTopic;

        Properties properties = new Properties();
        properties.setProperty("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.setProperty("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        if (params.kafkaTrafficPropertyFile != null) {
            try (InputStream input = new FileInputStream(params.kafkaTrafficPropertyFile)) {
                properties.load(input);
            } catch (IOException ex) {
                log.atError().setMessage(()->"Unable to load properties from kafka.properties file.").log();
                return;
            }
        }
        // Required for using SASL auth with MSK public endpoint
        if (params.kafkaTrafficEnableMSKAuth){
            properties.setProperty("security.protocol", "SASL_SSL");
            properties.setProperty("sasl.mechanism", "AWS_MSK_IAM");
            properties.setProperty("sasl.jaas.config", "software.amazon.msk.auth.iam.IAMLoginModule required;");
            properties.setProperty("sasl.client.callback.handler.class", "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
        }
        properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);

        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties);
        try {
            consumer.subscribe(Collections.singleton(topic));
            pipeRecordsToProtoBufDelimited(consumer, getDelimitedProtoBufOutputter(System.out));
        } catch (WakeupException e) {
            log.atInfo().setMessage(()->"Wake up exception!").log();
        } catch (Exception e) {
            log.atError().setCause(e).setMessage(()->"Unexpected exception").log();
        } finally {
            consumer.close();
            log.atInfo().setMessage(()->"This consumer close successfully.").log();
        }
    }

    static void pipeRecordsToProtoBufDelimited(
        Consumer<String, byte[]> kafkaConsumer,
                                               java.util.function.Consumer<Stream<byte[]>> binaryReceiver) {
        while (true) {
            processNextChunkOfKafkaEvents(kafkaConsumer, binaryReceiver);
        }
    }

    static void processNextChunkOfKafkaEvents(Consumer<String, byte[]> kafkaConsumer, java.util.function.Consumer<Stream<byte[]>> binaryReceiver) {
        var records = kafkaConsumer.poll(CONSUMER_POLL_TIMEOUT);
        binaryReceiver.accept(StreamSupport.stream(records.spliterator(), false)
                .map(cr->cr.value()));
    }

    static java.util.function.Consumer<Stream<byte[]>> getDelimitedProtoBufOutputter(OutputStream outputStream) {
        CodedOutputStream codedOutputStream = CodedOutputStream.newInstance(outputStream);

        return bufferStream -> {
            bufferStream.forEach(buffer -> {
                try {
                    codedOutputStream.writeUInt32NoTag(buffer.length);
                    codedOutputStream.writeRawBytes(buffer);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            try {
                codedOutputStream.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
    }
}