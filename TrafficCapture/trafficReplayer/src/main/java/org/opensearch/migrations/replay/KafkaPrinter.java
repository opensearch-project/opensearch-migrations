package org.opensearch.migrations.replay;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.protobuf.CodedOutputStream;
import lombok.Lombok;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    static class Partition {
        String topic;
        int partitionId;
        Partition(String topic, int partitionId) {
            this.topic = topic;
            this.partitionId = partitionId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(topic, partitionId);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            Partition partition = (Partition) obj;
            return partitionId == partition.partitionId && topic.equals(partition.topic);
        }
    }
    static class PartitionTracker {
        long currentRecordCount;
        long recordLimit;
        PartitionTracker(long currentRecordCount, long recordLimit) {
            this.currentRecordCount = currentRecordCount;
            this.recordLimit = recordLimit;
        }
    }

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
        @Parameter(required = false,
            names = {"--partition-limit"},
            description = "Partition limit option will only print records for a given partition up to the given limit " +
                "and will terminate the printer when all limits have been met. Can be used multiple times, and may be comma-separated. topic:partition:num_records")
        List<String> partitionLimits = new ArrayList<>();
        @Parameter(required = false,
            names = {"--timeout-seconds"},
            arity=1,
            description = "Timeout option.")
        long timeoutSeconds = 0;

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
                log.error("Unable to load properties from kafka.properties file.");
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

        Map<Partition, PartitionTracker> capturedRecords = new HashMap<>();
        if (!params.partitionLimits.isEmpty()) {
            for (String partitionLimit : params.partitionLimits) {
                String[] partitionElements = partitionLimit.split(":");
                if (partitionElements.length != 3) {
                    throw new ParameterException("Partition limit provided does not match the expected format: topic_name:partition_id:num_records, actual value: " + partitionLimit);
                }
                Partition partition = new Partition(partitionElements[0], Integer.parseInt(partitionElements[1]));
                if (capturedRecords.containsKey(partition)) {
                    throw new ParameterException("Duplicate parameter limit detected for the partition: " + partition);
                }
                capturedRecords.put(partition, new PartitionTracker(0, Long.parseLong(partitionElements[2])));
            }
        }

        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(Collections.singleton(topic));
            pipeRecordsToProtoBufDelimited(consumer, getDelimitedProtoBufOutputter(System.out, capturedRecords),
                params.timeoutSeconds, capturedRecords);
        } catch (WakeupException e) {
            log.info("Wake up exception!");
        } catch (Exception e) {
            log.error("Unexpected exception", e);
        } finally {
            log.info("This consumer close successfully.");
        }
    }

    static boolean checkAllRecordsCompleted(Collection<PartitionTracker> trackers) {
        for (PartitionTracker tracker : trackers) {
            if (tracker.currentRecordCount < tracker.recordLimit) {
                return false;
            }
        }
        return true;
    }

    static void pipeRecordsToProtoBufDelimited(
        Consumer<String, byte[]> kafkaConsumer, java.util.function.Consumer<Stream<ConsumerRecord<String, byte[]>>> binaryReceiver,
        long timeoutSeconds, Map<Partition, PartitionTracker> capturedRecords) {

        long endTime = System.currentTimeMillis() + (timeoutSeconds * 1000);
        boolean continueCapture = true;
        while (continueCapture) {
            if (!capturedRecords.isEmpty() && checkAllRecordsCompleted(capturedRecords.values())) {
                log.info("All partition limits have been met, stopping Kafka polls");
                continueCapture = false;
            }
            else if (timeoutSeconds > 0 && System.currentTimeMillis() >= endTime) {
                log.warn("Specified timeout of {} seconds has been breached, stopping Kafka polls", timeoutSeconds);
                continueCapture = false;
            }
            else {
                for (PartitionTracker pt : capturedRecords.values()) {
                    log.debug("Tracker is at {} records for limit {}", pt.currentRecordCount, pt.recordLimit);
                }
                processNextChunkOfKafkaEvents(kafkaConsumer, binaryReceiver);
            }
        }
    }

    static void processNextChunkOfKafkaEvents(Consumer<String, byte[]> kafkaConsumer, java.util.function.Consumer<Stream<ConsumerRecord<String, byte[]>>> binaryReceiver) {
        var records = kafkaConsumer.poll(CONSUMER_POLL_TIMEOUT);
        binaryReceiver.accept(StreamSupport.stream(records.spliterator(), false));
    }

    static java.util.function.Consumer<Stream<ConsumerRecord<String, byte[]>>> getDelimitedProtoBufOutputter(OutputStream outputStream, Map<Partition, PartitionTracker> capturedRecords) {
        CodedOutputStream codedOutputStream = CodedOutputStream.newInstance(outputStream);

        return consumerRecordStream -> {
            consumerRecordStream.forEach(consumerRecord -> {
                try {
                    Partition partition = new Partition(consumerRecord.topic(), consumerRecord.partition());
                    log.debug("Incoming record for topic:{} and partition:{}", partition.topic, partition.partitionId);
                    PartitionTracker tracker = capturedRecords.get(partition);
                    if (tracker != null) {
                        // Skip records past our limit
                        if (tracker.currentRecordCount >= tracker.recordLimit) {
                            return;
                        }
                        tracker.currentRecordCount++;
                    }
                    byte[] buffer = consumerRecord.value();
                    codedOutputStream.writeUInt32NoTag(buffer.length);
                    codedOutputStream.writeRawBytes(buffer);
                } catch (IOException e) {
                    throw Lombok.sneakyThrow(e);
                }
            });
            try {
                codedOutputStream.flush();
            } catch (IOException e) {
                throw Lombok.sneakyThrow(e);
            }
        };
    }
}