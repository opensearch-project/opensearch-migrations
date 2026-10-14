/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.utils.kafka;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.ExecutionException;

import lombok.NonNull;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

/**
 * Reads replay progress from Kafka metadata without fetching captured-traffic record values.
 *
 * <p>The replayer commits the timestamp of the last contiguously and durably replayed record in
 * {@link OffsetAndMetadata#metadata()}. Combining that watermark with Kafka's end offset and
 * maximum-timestamp lookup gives operators both ordinary offset lag and a temporal replay backlog. The
 * latter is not the age of the exact next unprocessed record; it is the timestamp span between replayed
 * progress and the newest record visible in the partition.</p>
 */
public final class KafkaConsumerGroupProgress {
    private static final String UNKNOWN = "-";

    private KafkaConsumerGroupProgress() {}

    public record PartitionProgress(
        @NonNull TopicPartition topicPartition,
        long committedOffset,
        long endOffset,
        OptionalLong replayedThroughTimestampMillis,
        OptionalLong latestTimestampMillis
    ) {
        long offsetLag() {
            return endOffset - committedOffset;
        }
    }

    public static List<PartitionProgress> query(
        @NonNull Admin admin,
        @NonNull String consumerGroup
    ) throws ExecutionException, InterruptedException {
        var committed = admin.listConsumerGroupOffsets(consumerGroup)
            .partitionsToOffsetAndMetadata()
            .get();
        if (committed.isEmpty()) {
            return List.of();
        }

        var endSpecs = new LinkedHashMap<TopicPartition, OffsetSpec>();
        var latestTimestampSpecs = new LinkedHashMap<TopicPartition, OffsetSpec>();
        committed.keySet().stream()
            .sorted(topicPartitionOrder())
            .forEach(topicPartition -> {
                endSpecs.put(topicPartition, OffsetSpec.latest());
                latestTimestampSpecs.put(topicPartition, OffsetSpec.maxTimestamp());
            });

        var endOffsetsFuture = admin.listOffsets(endSpecs).all();
        var latestTimestampsFuture = admin.listOffsets(latestTimestampSpecs).all();
        return combine(committed, endOffsetsFuture.get(), latestTimestampsFuture.get());
    }

    static List<PartitionProgress> combine(
        Map<TopicPartition, OffsetAndMetadata> committed,
        Map<TopicPartition, ListOffsetsResultInfo> endOffsets,
        Map<TopicPartition, ListOffsetsResultInfo> latestTimestamps
    ) {
        return committed.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(topicPartitionOrder()))
            .map(entry -> {
                var topicPartition = entry.getKey();
                var end = Objects.requireNonNull(
                    endOffsets.get(topicPartition),
                    () -> "Kafka returned no end offset for " + topicPartition
                );
                var latest = latestTimestamps.get(topicPartition);
                return new PartitionProgress(
                    topicPartition,
                    entry.getValue().offset(),
                    end.offset(),
                    parseReplayWatermark(entry.getValue().metadata()),
                    latest == null || latest.timestamp() < 0
                        ? OptionalLong.empty()
                        : OptionalLong.of(latest.timestamp())
                );
            })
            .toList();
    }

    public static String render(List<PartitionProgress> progress) {
        if (progress.isEmpty()) {
            return "REPLAY WATERMARK\nConsumer group has no committed offsets.\n";
        }

        var headers = List.of(
            "TOPIC",
            "PARTITION",
            "COMMITTED-OFFSET",
            "OFFSET-LAG",
            "REPLAYED-THROUGH (UTC)",
            "LATEST-RECORD (UTC)",
            "TEMPORAL-BACKLOG",
            "NOTE"
        );
        var rows = new ArrayList<List<String>>();
        for (var partition : progress) {
            rows.add(renderRow(partition));
        }

        var widths = new int[headers.size()];
        for (var column = 0; column < headers.size(); column++) {
            widths[column] = headers.get(column).length();
            for (var row : rows) {
                widths[column] = Math.max(widths[column], row.get(column).length());
            }
        }

        var output = new StringBuilder(
            "REPLAY WATERMARK (record timestamps; not authoritative Kafka backlog drain time)\n"
        );
        appendRow(output, headers, widths);
        for (var row : rows) {
            appendRow(output, row, widths);
        }
        return output.toString();
    }

    private static List<String> renderRow(PartitionProgress progress) {
        var offsetLag = progress.offsetLag();
        var replayedThrough = progress.replayedThroughTimestampMillis();
        var latest = progress.latestTimestampMillis();
        String renderedOffsetLag;
        String temporalBacklog;
        String note;
        if (offsetLag < 0) {
            renderedOffsetLag = UNKNOWN;
            temporalBacklog = UNKNOWN;
            note = "offsets changed during query; retry";
        } else if (offsetLag == 0) {
            renderedOffsetLag = "0";
            temporalBacklog = "0s";
            note = "caught up by offset";
        } else if (replayedThrough.isEmpty()) {
            renderedOffsetLag = Long.toString(offsetLag);
            temporalBacklog = UNKNOWN;
            note = "commit has no replay watermark";
        } else if (latest.isEmpty()) {
            renderedOffsetLag = Long.toString(offsetLag);
            temporalBacklog = UNKNOWN;
            note = "partition has no timestamped record";
        } else if (latest.getAsLong() < replayedThrough.getAsLong()) {
            renderedOffsetLag = Long.toString(offsetLag);
            temporalBacklog = UNKNOWN;
            note = "timestamp ordering inconsistent; retry";
        } else {
            renderedOffsetLag = Long.toString(offsetLag);
            temporalBacklog = formatDuration(
                Duration.ofMillis(latest.getAsLong() - replayedThrough.getAsLong())
            );
            note = "";
        }

        return List.of(
            progress.topicPartition().topic(),
            Integer.toString(progress.topicPartition().partition()),
            Long.toString(progress.committedOffset()),
            renderedOffsetLag,
            formatTimestamp(replayedThrough),
            formatTimestamp(latest),
            temporalBacklog,
            note
        );
    }

    private static OptionalLong parseReplayWatermark(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return OptionalLong.empty();
        }
        try {
            var timestamp = Long.parseLong(metadata);
            return timestamp < 0 ? OptionalLong.empty() : OptionalLong.of(timestamp);
        } catch (NumberFormatException ignored) {
            return OptionalLong.empty();
        }
    }

    private static String formatTimestamp(OptionalLong timestampMillis) {
        return timestampMillis.isEmpty()
            ? UNKNOWN
            : Instant.ofEpochMilli(timestampMillis.getAsLong()).toString();
    }

    private static String formatDuration(Duration duration) {
        var millis = duration.toMillis();
        if (millis < 1_000) {
            return millis + "ms";
        }
        var seconds = millis / 1_000;
        var days = seconds / 86_400;
        seconds %= 86_400;
        var hours = seconds / 3_600;
        seconds %= 3_600;
        var minutes = seconds / 60;
        seconds %= 60;
        var text = new StringBuilder();
        if (days > 0) {
            text.append(days).append('d');
        }
        if (hours > 0) {
            text.append(hours).append('h');
        }
        if (minutes > 0) {
            text.append(minutes).append('m');
        }
        if (seconds > 0 || text.isEmpty()) {
            text.append(seconds).append('s');
        }
        return text.toString();
    }

    private static void appendRow(StringBuilder output, List<String> values, int[] widths) {
        for (var column = 0; column < values.size(); column++) {
            if (column > 0) {
                output.append("  ");
            }
            output.append(String.format("%-" + widths[column] + "s", values.get(column)));
        }
        output.append('\n');
    }

    private static Comparator<TopicPartition> topicPartitionOrder() {
        return Comparator.comparing(TopicPartition::topic)
            .thenComparingInt(TopicPartition::partition);
    }
}
