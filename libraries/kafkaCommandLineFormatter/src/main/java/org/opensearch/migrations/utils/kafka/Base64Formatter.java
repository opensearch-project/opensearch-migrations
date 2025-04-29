package org.opensearch.migrations.utils.kafka;


import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.MessageFormatter;

public class Base64Formatter implements MessageFormatter {
    private static final String DELIMITER = "|";

    @Override
    public void writeTo(ConsumerRecord<byte[], byte[]> kafkaRecord, PrintStream out) {
        String key = new String(kafkaRecord.key(), StandardCharsets.UTF_8);
        String value = Base64.getEncoder().encodeToString(kafkaRecord.value());
        out.println(key + DELIMITER + value);
    }
}
