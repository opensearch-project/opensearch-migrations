package org.opensearch.migrations.replay;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.kafka.KafkaBehavioralPolicy;
import org.opensearch.migrations.replay.kafka.KafkaTrafficCaptureSource;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.InputStreamOfTraffic;

import java.io.FileInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

@Slf4j
public class TrafficCaptureSourceFactory {

    private TrafficCaptureSourceFactory() {}

    public static BlockingTrafficSource
    createTrafficCaptureSource(TrafficReplayer.Parameters appParams, Duration bufferTimeWindow) throws IOException {
        return new BlockingTrafficSource(createUnbufferedTrafficCaptureSource(appParams), bufferTimeWindow);
    }

    public static ISimpleTrafficCaptureSource
    createUnbufferedTrafficCaptureSource(TrafficReplayer.Parameters appParams) throws IOException {
        boolean isKafkaActive = TrafficReplayer.validateRequiredKafkaParams(appParams.kafkaTrafficBrokers, appParams.kafkaTrafficTopic, appParams.kafkaTrafficGroupId);
        boolean isInputFileActive = appParams.inputFilename != null;

        if (isInputFileActive && isKafkaActive) {
            throw new IllegalArgumentException("Only one traffic source can be specified, detected options for input file as well as Kafka");
        }

        if (isKafkaActive) {
            return KafkaTrafficCaptureSource.buildKafkaSource(
                    appParams.kafkaTrafficBrokers, appParams.kafkaTrafficTopic,
                    appParams.kafkaTrafficGroupId, appParams.kafkaTrafficEnableMSKAuth,
                    appParams.kafkaTrafficPropertyFile,
                    Clock.systemUTC(), new KafkaBehavioralPolicy());
        } else if (isInputFileActive) {
            return new InputStreamOfTraffic(new FileInputStream(appParams.inputFilename));
        } else {
            return new InputStreamOfTraffic(System.in);
        }
    }
}
