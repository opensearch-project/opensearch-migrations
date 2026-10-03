/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafka;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;

/**
 * Consumer properties assembled from CLI configuration, shared by everything in this module that opens a
 * Kafka consumer.
 */
@Slf4j
public class KafkaConsumerProperties {

    private KafkaConsumerProperties() {}

    public static Properties buildKafkaProperties(
        @NonNull String brokers,
        @NonNull String groupId,
        @NonNull String authType,
        String kafkaUserName,
        String kafkaPassword,
        String propertyFilePath
    ) throws IOException {
        var kafkaProps = new Properties();
        kafkaProps.setProperty("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        kafkaProps.setProperty("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        kafkaProps.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        kafkaProps.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        if (propertyFilePath != null) {
            try (InputStream input = new FileInputStream(propertyFilePath)) {
                kafkaProps.load(input);
            } catch (IOException ex) {
                log.error("Unable to load properties from kafka properties file with path: {}", propertyFilePath);
                throw ex;
            }
        }
        KafkaSaslAuthHelper.applySaslAuthProperties(kafkaProps, authType, kafkaUserName, kafkaPassword);
        kafkaProps.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        kafkaProps.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        // Use cooperative sticky rebalancing to avoid stop-the-world partition revocation
        kafkaProps.putIfAbsent(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
            "org.apache.kafka.clients.consumer.CooperativeStickyAssignor");
        return kafkaProps;
    }
}
