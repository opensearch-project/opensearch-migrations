/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafka;

import java.io.PrintStream;
import java.time.Instant;

import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.intake.SourceAssemblySink;
import org.opensearch.migrations.replay.util.TrafficChannelKeyFormatter;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Prints one line per reconstructed request, response and close, for {@code --mode dump-http}.
 *
 * <p>This is the cheapest reading of what source assembly produced: run it over a real topic and every
 * transaction the replayer reconstructed is a line a person can check. A close- or exception-bounded response
 * keeps its reconstructed bytes and prints {@code UNPROVEN}; {@code RSP INCOMPLETE} is reserved for expiration
 * and generation cancellation, where the signal deliberately carries no response bytes.
 *
 * <p>The {@code o:} and {@code s:} columns render blank here. They are the Kafka offset and
 * {@code TrafficStream} index of a single record, and a reconstructed transaction spans however many records
 * it spans — {@code dump-raw} is the per-record view and keeps them filled. The columns stay in the layout so
 * {@code dump-both} interleaves the two views in alignment.
 */
@Slf4j
public class HttpTransactionDumper implements SourceAssemblySink {

    private final PrintStream out;
    private final String linePrefix;

    public HttpTransactionDumper(PrintStream out) {
        this(out, "");
    }

    public HttpTransactionDumper(PrintStream out, String linePrefix) {
        this.out = out;
        this.linePrefix = linePrefix;
    }

    @Override
    public void onRequestReconstituted(
        @NonNull ReplayRequestId replayRequestId,
        long capturedRequestOrdinal,
        @NonNull HttpMessageAndTimestamp.Request request,
        @NonNull Instant sourceEventTime,
        long requestCompletingLogAppendTime
    ) {
        out.println(linePrefix
            + buildPrefix(
                replayRequestId.connectionProcessingId(),
                request.getFirstPacketTimestamp(),
                request.getLastPacketTimestamp())
            + " REQ[" + messageSize(request) + "] #" + capturedRequestOrdinal
            + " " + extractFirstLine(request));
    }

    /**
     * Prints a response, and marks it {@code UNPROVEN} when nothing proved the source finished writing it.
     *
     * <p>{@code kafkaLLD §9.2}: only a following request on the same connection proves completion. A response
     * ended by a close or a connection exception may have been truncated and there is no way to tell, so the
     * line says so rather than presenting it as though it were whole. That marker is the honest form of what
     * Plan A asks this output to show.
     */
    @Override
    public void onSourceResponseComplete(
        @NonNull ReplayRequestId replayRequestId,
        @NonNull HttpMessageAndTimestamp.Response response,
        boolean keptAlive
    ) {
        out.println(linePrefix
            + buildPrefix(
                replayRequestId.connectionProcessingId(),
                response.getFirstPacketTimestamp(),
                response.getLastPacketTimestamp())
            + " RSP[" + messageSize(response) + "]"
            + (keptAlive ? "" : " UNPROVEN")
            + " " + extractFirstLine(response));
    }

    @Override
    public void onSourceResponseIncomplete(
        @NonNull ReplayRequestId replayRequestId,
        @NonNull IncompleteReason reason
    ) {
        // No bytes, deliberately: the signal carries none, so this line cannot show a truncated response as
        // though it were a response.
        out.println(linePrefix
            + buildPrefix(replayRequestId.connectionProcessingId(), null, null)
            + " RSP INCOMPLETE (" + reason + ")");
    }

    @Override
    public void onCapturedClose(
        @NonNull ConnectionProcessingId connectionProcessingId,
        @NonNull Instant closeTime
    ) {
        out.println(linePrefix
            + buildPrefix(connectionProcessingId, closeTime, closeTime)
            + " CLOSED");
    }

    // Dynamic column widths — start with reasonable defaults, grow as needed
    private int tsWidth = 10;
    private int pWidth = 1;
    private int oWidth = 6;
    private int sWidth = 3;
    private long baseEpochSeconds = -1;

    public void setBaseEpochSeconds(long baseEpochSeconds) {
        if (this.baseEpochSeconds < 0) this.baseEpochSeconds = baseEpochSeconds;
    }

    private String relativeTime(long startEpoch, long endEpoch) {
        if (startEpoch == 0) return String.format("%6s    %3s   ", "", "");
        if (baseEpochSeconds < 0) baseEpochSeconds = startEpoch;
        var offset = startEpoch - baseEpochSeconds;
        var duration = endEpoch - startEpoch;
        return String.format("%6d.0s %3d.0s", offset, duration);
    }

    /**
     * All lines share the same column layout: {@code [ts-ts] p:N o:N s:N nc:node.conn:}, which is what lets
     * {@code dump-both} interleave these lines with {@code dump-raw}'s.
     */
    private String buildPrefix(ConnectionProcessingId connection, Instant first, Instant last) {
        var sb = new StringBuilder();
        long startEpoch = first != null ? first.getEpochSecond() : 0;
        long endEpoch = last != null ? last.getEpochSecond() : startEpoch;
        var startStr = String.valueOf(startEpoch);
        var endStr = String.valueOf(endEpoch);
        tsWidth = Math.max(tsWidth, Math.max(startStr.length(), endStr.length()));
        sb.append('[').append(pad(startStr, tsWidth)).append('-').append(pad(endStr, tsWidth)).append(']');
        sb.append(' ').append(relativeTime(startEpoch, endEpoch));

        var pStr = String.valueOf(connection.generation().topicPartition().partition());
        pWidth = Math.max(pWidth, pStr.length());
        sb.append(" p:").append(pad(pStr, pWidth));
        sb.append(" o:").append(dashPad(oWidth));
        sb.append(" s:").append(dashPad(sWidth));

        sb.append(" nc:").append(TrafficChannelKeyFormatter.format(
            connection.capturedConnectionId().writerNodeId(),
            connection.capturedConnectionId().connectionId())).append(':');
        return sb.toString();
    }

    private static String pad(String val, int width) {
        if (val.length() >= width) return val;
        return " ".repeat(width - val.length()) + val;
    }

    private static String dashPad(int width) {
        return " ".repeat(width);
    }

    private static long messageSize(HttpMessageAndTimestamp msg) {
        if (msg == null || msg.packetBytes == null) return 0;
        return msg.packetBytes.stream().mapToLong(b -> b.length).sum();
    }

    static String extractFirstLine(HttpMessageAndTimestamp msg) {
        if (msg == null || msg.packetBytes == null) return "<empty>";
        var iter = msg.packetBytes.stream().iterator();
        var sb = new StringBuilder();
        while (iter.hasNext()) {
            var chunk = iter.next();
            for (byte b : chunk) {
                if (b == '\r' || b == '\n') {
                    return sb.toString();
                }
                sb.append((char) (b & 0xff));
            }
        }
        return sb.toString();
    }
}
