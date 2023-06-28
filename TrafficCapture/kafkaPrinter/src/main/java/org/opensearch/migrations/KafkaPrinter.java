package org.opensearch.migrations;

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

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.FileInputStream;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class KafkaPrinter {
    private static final Logger log = LoggerFactory.getLogger(KafkaPrinter.class);
    public static final Duration CONSUMER_POLL_TIMEOUT = Duration.ofSeconds(1);

    static class Parameters {
        @Parameter(required = true,
                names = {"-b", "--broker-address"},
                description = "Broker's address")
        String brokerAddress;
        @Parameter(required = true,
                names = {"-t", "--topic-name"},
                description = "topic name")
        String topicName;
        @Parameter(required = true,
                names = {"-g", "--group-id"},
                description = "Client id that should be used when communicating with the Kafka broker.")
        String clientGroupId;
        @Parameter(required = false,
                names = {"--enableMSKAuth"},
                description = "Enables SASL properties required for connecting to MSK with IAM auth.")
        boolean mskAuthEnabled = false;
        @Parameter(required = false,
                names = {"--kafkaConfigFile"},
                arity = 1,
                description = "Kafka properties file")
        String kafkaPropertiesFile;
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

        String bootstrapServers = params.brokerAddress;
        String groupId = params.clientGroupId;
        String topic = params.topicName;

        Properties properties = new Properties();
        if (params.kafkaPropertiesFile != null) {
            try (InputStream input = new FileInputStream(params.kafkaPropertiesFile)) {
                properties.load(input);
            } catch (IOException ex) {
                log.error("Unable to load properties from kafka.properties file.");
                return;
            }
        }
        else {
            properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            properties.setProperty("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            properties.setProperty("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
            properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
            properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        }
        // Required for using SASL auth with MSK public endpoint
        if (params.mskAuthEnabled){
            properties.setProperty("security.protocol", "SASL_SSL");
            properties.setProperty("sasl.mechanism", "AWS_MSK_IAM");
            properties.setProperty("sasl.jaas.config", "software.amazon.msk.auth.iam.IAMLoginModule required;");
            properties.setProperty("sasl.client.callback.handler.class", "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
        }

        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties);

        try {
            consumer.subscribe(Collections.singleton(topic));
            pipeRecordsToProtoBufDelimited(consumer, getDelimitedProtoBufOutputter(System.out));
        } catch (WakeupException e) {
            log.info("Wake up exception!");
        } catch (Exception e) {
            log.error("Unexpected exception", e);
        } finally {
            consumer.close();
            log.info("This consumer close successfully.");
        }
    }

    static void pipeRecordsToProtoBufDelimited(org.apache.kafka.clients.consumer.Consumer<String, byte[]> kafkaConsumer,
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