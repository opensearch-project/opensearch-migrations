package org.opensearch.migrations.trafficcapture.protos;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Collectors;

import com.google.protobuf.Timestamp;

public class TrafficStreamUtils {

    private TrafficStreamUtils() {}

    public static Instant instantFromProtoTimestamp(Timestamp timestampProto) {
        return Instant.ofEpochSecond(timestampProto.getSeconds(), timestampProto.getNanos());
    }

    public static Optional<Instant> getFirstTimestamp(TrafficStream ts) {
        var substream = ts.getSubStreamList();
        return substream != null && !substream.isEmpty()
            ? Optional.of(instantFromProtoTimestamp(substream.get(0).getTs()))
            : Optional.empty();
    }

    public static String summarizeTrafficStream(TrafficStream ts) {
        var listSummaryStr = ts.getSubStreamList()
            .stream()
            .map(
                tso -> instantFromProtoTimestamp(tso.getTs())
                    + ": "
                    + captureCaseToString(tso.getCaptureCase())
                    + getOptionalContext(tso)
            )
            .collect(Collectors.joining(", "));
        return ts.getConnectionId() + " (#" + getTrafficStreamIndex(ts) + ")[" + listSummaryStr + "]";
    }

    private static Object getOptionalContext(TrafficObservation tso) {
        return Optional.ofNullable(getByteArrayForDataOf(tso))
            .map(b -> " " + new String(b, 0, Math.min(3, b.length), StandardCharsets.UTF_8))
            .orElse("");
    }

    private static byte[] getByteArrayForDataOf(TrafficObservation tso) {
        if (tso.hasRead()) {
            return tso.getRead().getData().toByteArray();
        } else if (tso.hasReadSegment()) {
            return tso.getReadSegment().getData().toByteArray();
        } else if (tso.hasWrite()) {
            return tso.getWrite().getData().toByteArray();
        } else if (tso.hasWriteSegment()) {
            return tso.getWriteSegment().getData().toByteArray();
        } else {
            return null;
        }
    }

    public static int getTrafficStreamIndex(TrafficStream ts) {
        return ts.hasNumber() ? ts.getNumber() : ts.getNumberOfThisLastChunk();
    }

    private static String captureCaseToString(TrafficObservation.CaptureCase captureCase) {
        switch (captureCase) {
            case ENDOFMESSAGEINDICATOR:
                return "EOM";
            default:
                return captureCase.toString();
        }
    }
}
