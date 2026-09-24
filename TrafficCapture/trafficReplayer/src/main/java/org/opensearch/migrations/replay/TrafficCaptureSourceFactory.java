package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G9) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: BlockingTrafficSource ISimpleTrafficCaptureSource RootReplayerContext . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G9)
/*

import java.io.FileInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

import org.opensearch.migrations.replay.kafka.KafkaTrafficCaptureSource;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.InputStreamOfTraffic;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TrafficCaptureSourceFactory {

    private TrafficCaptureSourceFactory() {}

    public static BlockingTrafficSource createTrafficCaptureSource(
        RootReplayerContext ctx,
        TrafficReplayer.Parameters appParams,
        Duration bufferTimeWindow
    ) throws IOException {
        return new BlockingTrafficSource(
            createUnbufferedTrafficCaptureSource(ctx, appParams),
            bufferTimeWindow
        );
    }

    public static ISimpleTrafficCaptureSource createUnbufferedTrafficCaptureSource(
        RootReplayerContext ctx,
        TrafficReplayer.Parameters appParams
    ) throws IOException {
        boolean isKafkaActive = TrafficReplayer.validateRequiredKafkaParams(
            appParams.kafkaTrafficBrokers,
            appParams.kafkaTrafficTopic,
            appParams.kafkaTrafficGroupId
        );
        boolean isInputFileActive = appParams.inputFilename != null;

        if (isInputFileActive && isKafkaActive) {
            throw new IllegalArgumentException(
                "Only one traffic source can be specified, detected options for input file as well as Kafka"
            );
        }

        if (isKafkaActive) {
            return KafkaTrafficCaptureSource.buildKafkaSource(
                ctx,
                appParams.kafkaTrafficBrokers,
                appParams.kafkaTrafficTopic,
                appParams.kafkaTrafficGroupId,
                appParams.getEffectiveKafkaAuthType(),
                appParams.kafkaTrafficUserName,
                appParams.kafkaTrafficPassword,
                appParams.kafkaTrafficPropertyFile,
                Clock.systemUTC(),
                appParams.maximumOwnedKafkaRecords,
                appParams.maximumOwnedKafkaBytes,
                !appParams.disableLivenessScanner
            );
        } else {
            return new InputStreamOfTraffic(
                ctx,
                isInputFileActive ? new FileInputStream(appParams.inputFilename) : System.in
            );
        }
    }
}

*/
// REBUILD-LIMBO-END(G9)
