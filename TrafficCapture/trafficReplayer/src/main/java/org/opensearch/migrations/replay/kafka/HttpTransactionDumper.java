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
import java.util.List;
import java.util.function.Consumer;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import org.opensearch.migrations.replay.AccumulationCallbacks;
import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.RequestResponsePacketPair;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.util.TrafficChannelKeyFormatter;

/**
 * AccumulationCallbacks implementation for dump-http mode.
 * Prints one line per request and one line per response to the given PrintStream.
 */
@Slf4j
public class HttpTransactionDumper implements AccumulationCallbacks {

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
    public Consumer<RequestResponsePacketPair> onRequestReceived(
        @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
        @NonNull HttpMessageAndTimestamp request,
        boolean isResumedConnection
    ) {
        var channelKey = ctx.getReplayerRequestKey().getTrafficStreamKey();
        var prefix = buildPrefix(channelKey, request.getFirstPacketTimestamp(), request.getLastPacketTimestamp());
        out.println(linePrefix + prefix + " REQ[" + messageSize(request) + "] " + extractFirstLine(request));

        return rrPair -> {
            if (rrPair.getResponseData() != null) {
                var rsp = rrPair.getResponseData();
                out.println(linePrefix + buildPrefix(channelKey, rsp.getFirstPacketTimestamp(), rsp.getLastPacketTimestamp())
                    + " RSP[" + messageSize(rsp) + "] " + extractFirstLine(rsp));
            }
        };
    }

    @Override
    public void onTrafficStreamsExpired(
        RequestResponsePacketPair.ReconstructionStatus status,
        @NonNull IReplayContexts.IChannelKeyContext ctx,
        @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
    ) {
        out.println(linePrefix + buildPrefixFromKeysAndCtx(trafficStreamKeysBeingHeld, ctx)
            + " EXPIRED (" + status + ")");
    }

    @Override
    public void onConnectionClose(
        int channelInteractionNum,
        @NonNull IReplayContexts.IChannelKeyContext ctx,
        int channelSessionNumber,
        RequestResponsePacketPair.ReconstructionStatus status,
        @NonNull Instant timestamp,
        @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
    ) {
        out.println(linePrefix + buildPrefixFromKeysAndCtx(trafficStreamKeysBeingHeld, ctx, timestamp)
            + " CLOSED (" + channelInteractionNum + " requests completed)");
    }

    @Override
    public void onTrafficStreamIgnored(@NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx) {
        // no-op
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
     * All lines share the same column layout: [ts-ts] p:N o:N s:N nc:node.conn:
     */
    private String buildPrefix(ISourceTrafficChannelKey channelKey, Instant first, Instant last) {
        var sb = new StringBuilder();
        long startEpoch = first != null ? first.getEpochSecond() : 0;
        long endEpoch = last != null ? last.getEpochSecond() : startEpoch;
        var startStr = String.valueOf(startEpoch);
        var endStr = String.valueOf(endEpoch);
        tsWidth = Math.max(tsWidth, Math.max(startStr.length(), endStr.length()));
        sb.append('[').append(pad(startStr, tsWidth)).append('-').append(pad(endStr, tsWidth)).append(']');
        sb.append(' ').append(relativeTime(startEpoch, endEpoch));

        if (channelKey instanceof KafkaCommitOffsetData) {
            var k = (KafkaCommitOffsetData) channelKey;
            var pStr = String.valueOf(k.getPartition());
            var oStr = String.valueOf(k.getOffset());
            pWidth = Math.max(pWidth, pStr.length());
            oWidth = Math.max(oWidth, oStr.length());
            sb.append(" p:").append(pad(pStr, pWidth));
            sb.append(" o:").append(pad(oStr, oWidth));
            if (channelKey instanceof ITrafficStreamKey) {
                var sStr = String.valueOf(((ITrafficStreamKey) channelKey).getTrafficStreamIndex());
                sWidth = Math.max(sWidth, sStr.length());
                sb.append(" s:").append(pad(sStr, sWidth));
            } else {
                sb.append(" s:").append(dashPad(sWidth));
            }
        } else {
            sb.append(" p:").append(dashPad(pWidth));
            sb.append(" o:").append(dashPad(oWidth));
            sb.append(" s:").append(dashPad(sWidth));
        }

        sb.append(" nc:").append(TrafficChannelKeyFormatter.format(
            channelKey.getNodeId(), channelKey.getConnectionId())).append(':');
        return sb.toString();
    }

    private static String pad(String val, int width) {
        if (val.length() >= width) return val;
        return " ".repeat(width - val.length()) + val;
    }

    private static String dashPad(int width) {
        return " ".repeat(width);
    }

    private String buildPrefixFromKeysAndCtx(
        List<ITrafficStreamKey> keys, IReplayContexts.IChannelKeyContext ctx
    ) {
        return buildPrefixFromKeysAndCtx(keys, ctx, null);
    }

    private String buildPrefixFromKeysAndCtx(
        List<ITrafficStreamKey> keys, IReplayContexts.IChannelKeyContext ctx, Instant timestamp
    ) {
        if (!keys.isEmpty()) {
            var tsk = keys.get(0);
            Instant ts = timestamp != null ? timestamp : Instant.EPOCH;
            return buildPrefix(tsk, ts, ts);
        }
        // Fallback when no keys are held — still emit consistent columns with space padding
        var sb = new StringBuilder();
        long epoch = timestamp != null ? timestamp.getEpochSecond() : 0;
        var epochStr = String.valueOf(epoch);
        tsWidth = Math.max(tsWidth, epochStr.length());
        sb.append('[').append(pad(epochStr, tsWidth)).append('-').append(pad(epochStr, tsWidth)).append(']');
        sb.append(' ').append(relativeTime(epoch, epoch));
        sb.append(" p:").append(dashPad(pWidth));
        sb.append(" o:").append(dashPad(oWidth));
        sb.append(" s:").append(dashPad(sWidth));
        sb.append(" nc:").append(TrafficChannelKeyFormatter.format(
            ctx.getNodeId(), ctx.getConnectionId())).append(':');
        return sb.toString();
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
