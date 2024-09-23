package org.opensearch.migrations.utils.kafka;


import java.io.PrintStream;
import java.util.Base64;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.MessageFormatter;

public class Base64Formatter implements MessageFormatter {
    @Override
    public void writeTo(ConsumerRecord<byte[], byte[]> record, PrintStream out) {
        out.println(Base64.getEncoder().encodeToString(record.value()));
    }

    @Override public void close() {}
}
