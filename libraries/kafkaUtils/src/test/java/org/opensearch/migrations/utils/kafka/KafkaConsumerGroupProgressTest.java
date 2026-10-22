/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.utils.kafka;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaConsumerGroupProgressTest {
    private static final TopicPartition PARTITION_0 = new TopicPartition("traffic", 0);
    private static final TopicPartition PARTITION_1 = new TopicPartition("traffic", 1);

    @Test
    void queriesAllPartitionsWithoutFetchingRecordValues() throws Exception {
        var admin = mock(Admin.class);
        var groupOffsets = mock(ListConsumerGroupOffsetsResult.class);
        var endOffsets = mock(ListOffsetsResult.class);
        var latestTimestamps = mock(ListOffsetsResult.class);
        var committed = Map.of(
            PARTITION_0, new OffsetAndMetadata(11, "1000"),
            PARTITION_1, new OffsetAndMetadata(21, "2000")
        );
        when(admin.listConsumerGroupOffsets("replayer")).thenReturn(groupOffsets);
        when(groupOffsets.partitionsToOffsetAndMetadata())
            .thenReturn(KafkaFuture.completedFuture(committed));
        when(admin.listOffsets(anyMap())).thenReturn(endOffsets, latestTimestamps);
        when(endOffsets.all()).thenReturn(KafkaFuture.completedFuture(Map.of(
            PARTITION_0, offsetInfo(15, -1),
            PARTITION_1, offsetInfo(21, -1)
        )));
        when(latestTimestamps.all()).thenReturn(KafkaFuture.completedFuture(Map.of(
            PARTITION_0, offsetInfo(14, 5_000),
            PARTITION_1, offsetInfo(20, 2_000)
        )));

        var progress = KafkaConsumerGroupProgress.query(admin, "replayer");

        Assertions.assertEquals(2, progress.size());
        Assertions.assertEquals(4, progress.get(0).offsetLag());
        Assertions.assertEquals(0, progress.get(1).offsetLag());
        verify(admin).listConsumerGroupOffsets("replayer");
        verify(admin, times(2)).listOffsets(anyMap());
    }

    @Test
    void rendersTemporalBacklogAndExplainsUnknownWatermarks() {
        var progress = KafkaConsumerGroupProgress.combine(
            Map.of(
                PARTITION_1, new OffsetAndMetadata(21, ""),
                PARTITION_0, new OffsetAndMetadata(11, "1700000000000")
            ),
            Map.of(
                PARTITION_0, offsetInfo(15, -1),
                PARTITION_1, offsetInfo(24, -1)
            ),
            Map.of(
                PARTITION_0, offsetInfo(14, 1700000125000L),
                PARTITION_1, offsetInfo(23, 1700000200000L)
            )
        );

        var rendered = KafkaConsumerGroupProgress.render(progress);

        Assertions.assertTrue(
            rendered.indexOf("traffic  0") < rendered.indexOf("traffic  1"),
            "output should be ordered by topic and partition"
        );
        Assertions.assertTrue(rendered.contains("2m5s"));
        Assertions.assertTrue(rendered.contains("commit has no replay watermark"));
        Assertions.assertTrue(rendered.contains("not authoritative Kafka backlog drain time"));
    }

    @Test
    void caughtUpPartitionReportsPositiveZeroAndRacingMetadataReportsUnknown() {
        var caughtUp = new KafkaConsumerGroupProgress.PartitionProgress(
            PARTITION_0,
            11,
            11,
            java.util.OptionalLong.empty(),
            java.util.OptionalLong.empty()
        );
        var raced = new KafkaConsumerGroupProgress.PartitionProgress(
            PARTITION_1,
            20,
            21,
            java.util.OptionalLong.of(2_000),
            java.util.OptionalLong.of(1_000)
        );

        var rendered = KafkaConsumerGroupProgress.render(List.of(caughtUp, raced));

        Assertions.assertTrue(rendered.contains("caught up by offset"));
        Assertions.assertTrue(rendered.contains("timestamp ordering inconsistent; retry"));
        Assertions.assertFalse(rendered.contains("-1s"));
    }

    @Test
    void aCommittedOffsetBeyondTheObservedEndIsNotRenderedAsCaughtUp() {
        var inconsistent = new KafkaConsumerGroupProgress.PartitionProgress(
            PARTITION_0,
            12,
            11,
            java.util.OptionalLong.of(1_000),
            java.util.OptionalLong.of(2_000)
        );

        var rendered = KafkaConsumerGroupProgress.render(List.of(inconsistent));

        Assertions.assertTrue(rendered.contains("offsets changed during query; retry"));
        Assertions.assertFalse(rendered.contains("caught up by offset"));
    }

    @Test
    void rejectsMissingEndOffsetsRatherThanReportingAFalseZeroLag() {
        var committed = Map.of(PARTITION_0, new OffsetAndMetadata(11, "1000"));

        var failure = Assertions.assertThrows(
            NullPointerException.class,
            () -> KafkaConsumerGroupProgress.combine(committed, Map.of(), Map.of())
        );

        Assertions.assertTrue(failure.getMessage().contains(PARTITION_0.toString()));
    }

    @Test
    void treatsNegativeReplayWatermarkAsUnknown() {
        var progress = KafkaConsumerGroupProgress.combine(
            Map.of(PARTITION_0, new OffsetAndMetadata(11, "-1")),
            Map.of(PARTITION_0, offsetInfo(12, -1)),
            Map.of(PARTITION_0, offsetInfo(11, 2_000))
        );

        Assertions.assertTrue(
            KafkaConsumerGroupProgress.render(progress)
                .contains("commit has no replay watermark")
        );
    }

    @Test
    void commandLineAcceptsMetadataOnlyProgressModeWithoutAnInputFile() {
        var parameters = KafkaLoaderFromFile.parseArgs(new String[] {
            "--consumerGroupProgress", "replayer",
            "--kafkaBrokers", "kafka:9092"
        });

        Assertions.assertEquals("replayer", parameters.consumerGroupProgress);
        Assertions.assertEquals("kafka:9092", parameters.kafkaParameters.kafkaBrokers);
        Assertions.assertNull(parameters.inputFile);
        Assertions.assertFalse(parameters.stdin);
    }

    private static ListOffsetsResultInfo offsetInfo(long offset, long timestamp) {
        return new ListOffsetsResultInfo(offset, timestamp, Optional.empty());
    }
}
