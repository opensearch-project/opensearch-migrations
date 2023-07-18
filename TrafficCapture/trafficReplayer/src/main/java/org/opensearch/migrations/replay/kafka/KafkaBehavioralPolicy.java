package org.opensearch.migrations.replay.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

@Slf4j
public class KafkaBehavioralPolicy {

    // Default behavior to log and ignore invalid records
    public TrafficStream onInvalidKafkaRecord(ConsumerRecord<String, byte[]> record, InvalidProtocolBufferException e) {
        log.error("Unable to parse incoming traffic stream with error: ", e);
        return null;
    }
}
