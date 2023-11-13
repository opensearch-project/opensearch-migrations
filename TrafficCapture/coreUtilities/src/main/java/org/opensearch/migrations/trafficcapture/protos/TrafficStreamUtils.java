package org.opensearch.migrations.trafficcapture.protos;

import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Collectors;

public class TrafficStreamUtils {

    private TrafficStreamUtils() {}

    public static Instant instantFromProtoTimestamp(Timestamp timestampProto) {
        return Instant.ofEpochSecond(timestampProto.getSeconds(), timestampProto.getNanos());
    }

    public static Optional<Instant> getFirstTimestamp(TrafficStream ts) {
        var substream = ts.getSubStreamList();
        return substream != null && substream.size() > 0 ?
                Optional.of(instantFromProtoTimestamp(substream.get(0).getTs())) :
                Optional.empty();
    }

    public static Optional<Instant> getLastTimestamp(TrafficStream ts) {
        var substream = ts.getSubStreamList();
        return substream != null && substream.size() > 0 ?
                Optional.of(instantFromProtoTimestamp(substream.get(substream.size()-1).getTs())) :
                Optional.empty();
    }

    public static String summarizeTrafficStream(TrafficStream ts) {
        var listSummaryStr = ts.getSubStreamList().stream()
                .map(tso->instantFromProtoTimestamp(tso.getTs()) + ": " + captureCaseToString(tso.getCaptureCase()))
                .collect(Collectors.joining(", "));
        return ts.getConnectionId() + " (#" + getTrafficStreamIndex(ts) + ")[" + listSummaryStr + "]";
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
