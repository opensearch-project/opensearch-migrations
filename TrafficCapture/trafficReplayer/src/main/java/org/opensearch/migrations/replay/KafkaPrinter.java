package org.opensearch.migrations.replay;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.google.protobuf.CodedOutputStream;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import lombok.Lombok;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    static class PartitionTracker {
        long currentRecordCount;
        long recordLimit;

        PartitionTracker(long currentRecordCount, long recordLimit) {
            this.currentRecordCount = currentRecordCount;
            this.recordLimit = recordLimit;
        }
    }

    static class Parameters {
        @Parameter(required = true, names = {
            "--kafka-traffic-brokers" }, arity = 1, description = "Comma-separated list of host and port pairs that are the addresses of the Kafka brokers to bootstrap with e.g. 'localhost:9092,localhost2:9092'")
        String kafkaTrafficBrokers;
        @Parameter(required = true, names = {
            "--kafka-traffic-topic" }, arity = 1, description = "Topic name used to pull messages from Kafka")
        String kafkaTrafficTopic;
        @Parameter(required = true, names = {
            "--kafka-traffic-group-id" }, arity = 1, description = "Consumer group id that is used when pulling messages from Kafka")
        String kafkaTrafficGroupId;
        @Parameter(required = false, names = {
            "--kafka-traffic-enable-msk-auth" }, arity = 0, description = "Enables SASL properties required for connecting to MSK with IAM auth")
        boolean kafkaTrafficEnableMSKAuth;
        @Parameter(required = false, names = {
            "--kafka-traffic-property-file" }, arity = 1, description = "File path for Kafka properties file to use for additional or overriden Kafka properties")
        String kafkaTrafficPropertyFile;
        @Parameter(required = false, names = {
            "--partition-limits" }, description = "Partition limit option will only print records for the provided partitions and up to the given limit "
                + "specified. It will terminate the printer when all limits have been met. Argument can be used multiple "
                + "times, and may be comma-separated, e.g. 'test-topic:0:10, test-topic:1:32' ")
        List<String> partitionLimits = new ArrayList<>();
        @Parameter(required = false, names = {
            "--timeout-seconds" }, arity = 1, description = "Timeout option for how long KafkaPrinter will continue to read from Kafka before terminating.")
        long timeoutSeconds = 0;
        @Parameter(required = false, names = {
            "--output-directory" }, arity = 1, description = "If provided will place output inside file(s) within this directory. Otherwise, output will be sent to STDOUT")
        String outputDirectoryPath;
        @Parameter(required = false, names = {
            "--combine-partition-output" }, arity = 0, description = "Creates a single output file with output from all partitions combined. Requires '--output-directory' to be specified.")
        boolean combinePartitionOutput;
        @Parameter(required = false, names = {
            "--partition-offsets" }, description = "Partition offsets to start consuming from. Defaults to first offset in partition. Format: 'topic_name:partition_id:offset,topic_name:partition_id:offset'")
        List<String> partitionOffsets = new ArrayList<>();

    }

    public static Parameters parseArgs(String[] args) {
        Parameters p = new Parameters();
        JCommander jCommander = new JCommander(p);
        try {
            jCommander.parse(args);
            return p;
        } catch (ParameterException e) {
            System.err.println(e.getMessage());
            System.err.println("Got args: " + String.join("; ", args));
            jCommander.usage();
            throw e;
        }
    }

    public static void main(String[] args) throws FileNotFoundException {
        Parameters params;
        try {
            params = parseArgs(args);
        } catch (ParameterException e) {
            return;
        }

        if (params.combinePartitionOutput && params.outputDirectoryPath == null) {
            throw new ParameterException(
                "The '--output-directory' parameter is required for using '--combine-partition-output'."
            );
        }

        String bootstrapServers = params.kafkaTrafficBrokers;
        String groupId = params.kafkaTrafficGroupId;
        String topic = params.kafkaTrafficTopic;

        Properties properties = new Properties();
        properties.setProperty("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.setProperty("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");

        if (params.kafkaTrafficPropertyFile != null) {
            try (InputStream input = new FileInputStream(params.kafkaTrafficPropertyFile)) {
                properties.load(input);
            } catch (IOException ex) {
                log.error("Unable to load properties from kafka.properties file.");
                return;
            }
        }
        // Required for using SASL auth with MSK public endpoint
        if (params.kafkaTrafficEnableMSKAuth) {
            properties.setProperty("security.protocol", "SASL_SSL");
            properties.setProperty("sasl.mechanism", "AWS_MSK_IAM");
            properties.setProperty("sasl.jaas.config", "software.amazon.msk.auth.iam.IAMLoginModule required;");
            properties.setProperty(
                "sasl.client.callback.handler.class",
                "software.amazon.msk.auth.iam.IAMClientCallbackHandler"
            );
        }
        properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);

        Map<TopicPartition, PartitionTracker> capturedRecords = new HashMap<>();
        if (!params.partitionLimits.isEmpty()) {
            for (String partitionLimit : params.partitionLimits) {
                String[] partitionElements = partitionLimit.split(":");
                if (partitionElements.length != 3) {
                    throw new ParameterException(
                        "Partition limit provided does not match the expected format: topic_name:partition_id:num_records, actual value: "
                            + partitionLimit
                    );
                }
                TopicPartition partition = new TopicPartition(
                    partitionElements[0],
                    Integer.parseInt(partitionElements[1])
                );
                if (capturedRecords.containsKey(partition)) {
                    throw new ParameterException("Duplicate parameter limit detected for the partition: " + partition);
                }
                capturedRecords.put(partition, new PartitionTracker(0, Long.parseLong(partitionElements[2])));
            }
        }

        Map<TopicPartition, Long> startingOffsets = new HashMap<>();
        if (!params.partitionOffsets.isEmpty()) {
            for (String partitionOffset : params.partitionOffsets) {
                String[] elements = partitionOffset.split(":");
                if (elements.length != 3) {
                    throw new ParameterException(
                        "Partition offset provided does not match the expected format: topic_name:partition_id:offset, actual value: "
                            + partitionOffset
                    );
                }
                TopicPartition partition = new TopicPartition(elements[0], Integer.parseInt(elements[1]));
                long offset = Long.parseLong(elements[2]);
                startingOffsets.put(partition, offset);
            }
        }

        String baseOutputPath = params.outputDirectoryPath == null ? "./" : params.outputDirectoryPath;
        baseOutputPath = !baseOutputPath.endsWith(File.separator) ? baseOutputPath + File.separator : baseOutputPath;
        String uuid = UUID.randomUUID().toString();
        boolean separatePartitionOutputs = false;
        Map<Integer, CodedOutputStream> partitionOutputStreams = new HashMap<>();
        // Grab all partition records
        if (capturedRecords.isEmpty()) {
            OutputStream os = params.outputDirectoryPath == null
                ? System.out
                : new FileOutputStream(
                    String.format("%s%s_%s_%s.proto", baseOutputPath, params.kafkaTrafficTopic, "all", uuid)
                );
            partitionOutputStreams.put(0, CodedOutputStream.newInstance(os));
        }
        // Only grab specific partition records based on limits
        else {
            if (params.combinePartitionOutput || params.outputDirectoryPath == null) {
                OutputStream os = params.outputDirectoryPath == null
                    ? System.out
                    : new FileOutputStream(
                        String.format("%s%s_%s_%s.proto", baseOutputPath, params.kafkaTrafficTopic, "all", uuid)
                    );
                partitionOutputStreams.put(0, CodedOutputStream.newInstance(os));
            } else {
                for (TopicPartition partition : capturedRecords.keySet()) {
                    separatePartitionOutputs = true;
                    FileOutputStream fos = new FileOutputStream(
                        String.format(
                            "%s%s_%d_%s.proto",
                            baseOutputPath,
                            partition.topic(),
                            partition.partition(),
                            uuid
                        )
                    );
                    partitionOutputStreams.put(partition.partition(), CodedOutputStream.newInstance(fos));
                }
            }
        }

        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(Collections.singleton(topic), new ConsumerRebalanceListener() {
                private final Set<TopicPartition> partitionsAssignedAtSomeTime = new HashSet<>();

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    log.info("Partitions Assigned: {}", partitions);

                    // Seek partitions assigned for the first time to the beginning
                    var partitionsAssignedFirstTime = new HashSet<>(partitions);
                    partitionsAssignedFirstTime.retainAll(partitionsAssignedAtSomeTime);
                    consumer.seekToBeginning(partitionsAssignedFirstTime);
                    partitionsAssignedAtSomeTime.addAll(partitionsAssignedFirstTime);

                    // Seek partitions to provided offset if current reader is earlier
                    partitions.forEach(partition -> {
                        Long offset = startingOffsets.get(partition);
                        var currentOffset = consumer.position(partition);
                        if (offset == null) {
                            log.info("Did not find specified startingOffset for partition {}", partition);
                        } else if (currentOffset < offset) {
                            consumer.seek(partition, offset);
                            log.info(
                                "Found a specified startingOffset for partition {} that is greater than "
                                    + "current offset {}. Seeking to {}",
                                partition,
                                currentOffset,
                                offset
                            );
                        } else {
                            log.info(
                                "Not changing fetch offsets because current offset is {} and startingOffset is {} "
                                    + "for partition {}",
                                currentOffset,
                                offset,
                                partition
                            );

                        }
                    });
                }

                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {}
            });
            pipeRecordsToProtoBufDelimited(
                consumer,
                getDelimitedProtoBufOutputter(capturedRecords, partitionOutputStreams, separatePartitionOutputs),
                params.timeoutSeconds,
                capturedRecords
            );
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
        Consumer<String, byte[]> kafkaConsumer,
        java.util.function.Consumer<Stream<ConsumerRecord<String, byte[]>>> binaryReceiver,
        long timeoutSeconds,
        Map<TopicPartition, PartitionTracker> capturedRecords
    ) {

        long endTime = System.currentTimeMillis() + (timeoutSeconds * 1000);
        boolean continueCapture = true;
        while (continueCapture) {
            if (!capturedRecords.isEmpty() && checkAllRecordsCompleted(capturedRecords.values())) {
                log.info("All partition limits have been met, stopping Kafka polls");
                continueCapture = false;
            } else if (timeoutSeconds > 0 && System.currentTimeMillis() >= endTime) {
                log.warn("Specified timeout of {} seconds has been breached, stopping Kafka polls", timeoutSeconds);
                continueCapture = false;
            } else {
                for (PartitionTracker pt : capturedRecords.values()) {
                    log.debug("Tracker is at {} records for limit {}", pt.currentRecordCount, pt.recordLimit);
                }
                processNextChunkOfKafkaEvents(kafkaConsumer, binaryReceiver);
            }
        }
    }

    static void processNextChunkOfKafkaEvents(
        Consumer<String, byte[]> kafkaConsumer,
        java.util.function.Consumer<Stream<ConsumerRecord<String, byte[]>>> binaryReceiver
    ) {
        var records = kafkaConsumer.poll(CONSUMER_POLL_TIMEOUT);
        binaryReceiver.accept(StreamSupport.stream(records.spliterator(), false));
    }

    static java.util.function.Consumer<Stream<ConsumerRecord<String, byte[]>>> getDelimitedProtoBufOutputter(
        Map<TopicPartition, PartitionTracker> capturedRecords,
        Map<Integer, CodedOutputStream> partitionOutputStreams,
        boolean separatePartitionOutputs
    ) {

        Set<CodedOutputStream> usedCodedOutputStreams = new HashSet<>();

        return consumerRecordStream -> {
            consumerRecordStream.forEach(consumerRecord -> {
                try {
                    // No partition limits case, output everything
                    if (capturedRecords.isEmpty()) {
                        CodedOutputStream codedOutputStream = partitionOutputStreams.get(0);
                        usedCodedOutputStreams.add(codedOutputStream);
                        byte[] buffer = consumerRecord.value();
                        codedOutputStream.writeUInt32NoTag(buffer.length);
                        codedOutputStream.writeRawBytes(buffer);
                    } else {
                        TopicPartition partition = new TopicPartition(
                            consumerRecord.topic(),
                            consumerRecord.partition()
                        );
                        log.debug(
                            "Incoming record for topic:{} and partition:{}",
                            partition.topic(),
                            partition.partition()
                        );
                        PartitionTracker tracker = capturedRecords.get(partition);
                        boolean outputNeeded = false;
                        if (tracker != null && tracker.currentRecordCount < tracker.recordLimit) {
                            tracker.currentRecordCount++;
                            outputNeeded = true;
                        }
                        if (outputNeeded) {
                            CodedOutputStream codedOutputStream = separatePartitionOutputs
                                ? partitionOutputStreams.get(partition.partition())
                                : partitionOutputStreams.get(0);
                            usedCodedOutputStreams.add(codedOutputStream);
                            byte[] buffer = consumerRecord.value();
                            codedOutputStream.writeUInt32NoTag(buffer.length);
                            codedOutputStream.writeRawBytes(buffer);
                        }
                    }
                } catch (IOException e) {
                    throw Lombok.sneakyThrow(e);
                }
            });
            try {
                for (CodedOutputStream cos : usedCodedOutputStreams) {
                    cos.flush();
                }
            } catch (IOException e) {
                throw Lombok.sneakyThrow(e);
            }
        };
    }
}
