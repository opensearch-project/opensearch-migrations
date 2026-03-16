/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafka;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.opensearch.migrations.replay.util.TrafficChannelKeyFormatter;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;

/**
 * Formats a single TrafficStream record as one line of text for the dump-raw mode.
 * No cross-record aggregation — each TrafficStream is self-contained.
 */
public class TrafficStreamDumper {

    private TrafficStreamDumper() {}

    /**
     * Format a TrafficStream record into a single summary line.
     *
     * @param ts             the TrafficStream record
     * @param partition      Kafka partition (-1 for file input)
     * @param offset         Kafka offset (-1 for file input)
     * @param previewRead    max bytes to preview for read data
     * @param previewWrite   max bytes to preview for write data
     */
    public static String format(TrafficStream ts, int partition, long offset,
                                int previewRead, int previewWrite) {
        return format(ts, partition, offset, previewRead, previewWrite, -1);
    }

    public static String format(TrafficStream ts, int partition, long offset,
                                int previewRead, int previewWrite, long baseEpochSeconds) {
        var sb = new StringBuilder();

        long minEpoch = Long.MAX_VALUE;
        long maxEpoch = Long.MIN_VALUE;
        for (var obs : ts.getSubStreamList()) {
            long sec = obs.getTs().getSeconds();
            if (sec < minEpoch) minEpoch = sec;
            if (sec > maxEpoch) maxEpoch = sec;
        }
        if (minEpoch == Long.MAX_VALUE) {
            sb.append("[?-?]");
        } else {
            sb.append('[').append(minEpoch).append('-').append(maxEpoch).append(']');
            if (baseEpochSeconds >= 0) {
                sb.append(String.format(" %6d.0s %3d.0s", minEpoch - baseEpochSeconds, maxEpoch - minEpoch));
            }
        }

        sb.append(' ');
        if (partition >= 0) {
            sb.append(String.format("p:%d o:%6d ", partition, offset));
        }

        sb.append("ncs:").append(TrafficChannelKeyFormatter.format(
            ts.getNodeId(), ts.getConnectionId(), TrafficStreamUtils.getTrafficStreamIndex(ts))).append(':');

        var observations = ts.getSubStreamList();
        int i = 0;
        while (i < observations.size()) {
            var obs = observations.get(i);
            if (isRead(obs)) {
                i = appendCoalesced(sb, observations, i, true, previewRead);
            } else if (isWrite(obs)) {
                i = appendCoalesced(sb, observations, i, false, previewWrite);
            } else {
                sb.append(' ').append(tokenFor(obs));
                i++;
            }
        }

        return sb.toString();
    }

    private static boolean isRead(TrafficObservation obs) {
        return obs.hasRead() || obs.hasReadSegment();
    }

    private static boolean isWrite(TrafficObservation obs) {
        return obs.hasWrite() || obs.hasWriteSegment();
    }

    /**
     * Coalesce consecutive read or write observations into a single token.
     * SegmentEnd observations are absorbed into the current run since they're
     * just internal framing from the capture proxy's chunking mechanism.
     * Returns the index past the last coalesced observation.
     */
    private static int appendCoalesced(StringBuilder sb, List<TrafficObservation> observations,
                                       int start, boolean reads, int previewBytes) {
        var allBytes = new ArrayList<byte[]>();
        int totalSize = 0;
        int i = start;
        while (i < observations.size()) {
            var obs = observations.get(i);
            if (obs.hasSegmentEnd()) {
                i++; // absorb into current run
            } else {
                byte[] data = reads ? getReadData(obs) : getWriteData(obs);
                if (data.length == 0) { break; }
                allBytes.add(data);
                totalSize += data.length;
                i++;
            }
        }

        sb.append(' ').append(reads ? 'R' : 'W').append('[').append(totalSize).append(']');
        if (previewBytes > 0 && totalSize > 0) {
            sb.append(": ").append(buildPreview(allBytes, previewBytes));
        }
        return i;
    }

    private static byte[] getReadData(TrafficObservation obs) {
        if (obs.hasRead()) return obs.getRead().getData().toByteArray();
        if (obs.hasReadSegment()) return obs.getReadSegment().getData().toByteArray();
        return new byte[0];
    }

    private static byte[] getWriteData(TrafficObservation obs) {
        if (obs.hasWrite()) return obs.getWrite().getData().toByteArray();
        if (obs.hasWriteSegment()) return obs.getWriteSegment().getData().toByteArray();
        return new byte[0];
    }

    static String buildPreview(List<byte[]> chunks, int maxBytes) {
        var buf = new byte[maxBytes];
        int copied = 0;
        for (var chunk : chunks) {
            int toCopy = Math.min(chunk.length, maxBytes - copied);
            System.arraycopy(chunk, 0, buf, copied, toCopy);
            copied += toCopy;
            if (copied >= maxBytes) break;
        }
        // Replace non-printable chars with '.'
        for (int j = 0; j < copied; j++) {
            if (buf[j] < 0x20 || buf[j] > 0x7e) buf[j] = '.';
        }
        var preview = new String(buf, 0, copied, StandardCharsets.US_ASCII);
        if (copied < totalSize(chunks)) {
            return preview + "...";
        }
        return preview;
    }

    private static int totalSize(List<byte[]> chunks) {
        int total = 0;
        for (var c : chunks) total += c.length;
        return total;
    }

    private static String tokenFor(TrafficObservation obs) {
        if (obs.hasConnect() || obs.hasBind()) return "OPEN";
        if (obs.hasClose()) return "CLOSE";
        if (obs.hasDisconnect()) return "DISCONNECT";
        if (obs.hasEndOfMessageIndicator()) return "EOM";
        if (obs.hasRequestDropped()) return "DROPPED";
        if (obs.hasConnectionException()) return "EXCEPTION";
        if (obs.hasSegmentEnd()) return "SEGMENT_END"; // shouldn't normally appear; absorbed during coalescing
        if (obs.hasRequestReleasedDownstream()) return "REQUEST_RELEASED";
        return "UNKNOWN";
    }
}
