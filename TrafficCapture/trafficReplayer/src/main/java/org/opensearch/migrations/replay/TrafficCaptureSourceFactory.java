package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.kafka.KafkaBehavioralPolicy;
import org.opensearch.migrations.replay.kafka.KafkaProtobufConsumer;

import java.io.FileInputStream;
import java.io.IOException;
import java.time.Duration;

@Slf4j
public class TrafficCaptureSourceFactory {

    public static BlockingTrafficSource
    createTrafficCaptureSource(TrafficReplayer.Parameters appParams, Duration bufferTimeWindow) throws IOException {
        return new BlockingTrafficSource(createUnbufferedTrafficCaptureSource(appParams), bufferTimeWindow);
    }

    public static ITrafficCaptureSource
    createUnbufferedTrafficCaptureSource(TrafficReplayer.Parameters appParams) throws IOException {
        boolean isKafkaActive = TrafficReplayer.validateRequiredKafkaParams(appParams.kafkaTrafficBrokers, appParams.kafkaTrafficTopic, appParams.kafkaTrafficGroupId);
        boolean isInputFileActive = appParams.inputFilename != null;

        if (isInputFileActive && isKafkaActive) {
            throw new RuntimeException("Only one traffic source can be specified, detected options for input file as well as Kafka");
        }

        if (isKafkaActive) {
            return KafkaProtobufConsumer.buildKafkaConsumer(appParams.kafkaTrafficBrokers, appParams.kafkaTrafficTopic,
                appParams.kafkaTrafficGroupId, appParams.kafkaTrafficEnableMSKAuth, appParams.kafkaTrafficPropertyFile, new KafkaBehavioralPolicy());
        } else if (isInputFileActive) {
            return new InputStreamOfTraffic(new FileInputStream(appParams.inputFilename));
        } else {
            return new InputStreamOfTraffic(System.in);
        }
    }
}
