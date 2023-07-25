package org.opensearch.migrations.replay.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;

@Slf4j
public class KafkaBehavioralPolicy {

    /**
     * This policy determines how we should handle Kafka records that are received and can't be parsed into a
     * TrafficStream protobuf object. The default implementation here simply returns null(which the caller will ignore)
     * instead of returning an Exception for the caller to throw.
     *
     * @param record The record unable to be parsed
     * @param e The exception encountered when parsing the record
     * @return Null if no exception should be thrown, otherwise provide the exception that will be thrown by the calling class
     */
    public RuntimeException onInvalidKafkaRecord(ConsumerRecord<String, byte[]> record, InvalidProtocolBufferException e) {
        log.error("Unable to parse incoming traffic stream with record id: {} from error: ", record.key(), e);
        return null;
    }
}
