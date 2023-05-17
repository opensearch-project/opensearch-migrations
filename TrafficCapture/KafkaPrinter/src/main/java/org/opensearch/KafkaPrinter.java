package org.opensearch;


import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import java.util.ArrayList;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;

public class KafkaPrinter {
    private static final Logger log = LoggerFactory.getLogger(KafkaPrinter.class);

    static class Parameters {
        @Parameter(required = true,
                names = {"-b", "--broker-address"},
                description = "Broker's address")
        String brokerAddress;
        @Parameter(required = true,
                names = {"-t", "--topic-name"},
                description = "topic name")
        String topicName;
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
            return null;
        }
    }

    public static void main(String[] args) {
        var params = parseArgs(args);

        String bootstrapServers = params.brokerAddress;
        String groupId = "default-logging-group";
        String topic = params.topicName;

        Properties properties = new Properties();
        properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.setProperty("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.setProperty("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties);

        try {
            consumer.subscribe(Collections.singleton(topic));

            while (true) {
                ConsumerRecords<String, byte[]> records =
                        consumer.poll(Duration.ofMillis(100));

                for (ConsumerRecord<String, byte[]> record : records) {
                    System.out.println(record.value().toString());
                }
            }
        } catch (WakeupException e) {
            log.info("Wake up exception!");
        } catch (Exception e) {
            log.error("Unexpected exception", e);
        } finally {
            consumer.close();
            log.info("This consumer close successfully.");
        }
    }
}