package org.opensearch.migrations.trafficcapture.protos;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Collectors;

import com.google.protobuf.Timestamp;

public class TrafficStreamUtils {

    private TrafficStreamUtils() {}

    /**
     * captureFormatVersion sentinel for the H1-only schema. Empty string and "v1" are
     * treated identically (proto3 default-empty string = legacy v1 capture).
     */
    public static final String CAPTURE_FORMAT_VERSION_V1 = "v1";

    /**
     * captureFormatVersion sentinel for the schema that may contain HTTP/2 frame observations
     * and ALPN observations. Set by capture proxies built with HTTP/2 support; consumed by
     * H2-aware replayers.      */
    public static final String CAPTURE_FORMAT_VERSION_V2 = "v2";

    /**
     * System property toggling whether this JVM is permitted to consume v2 (HTTP/2-capable)
     * captures. Defaults to {@code false} until the replayer's H2 accumulator path lands
     * . When the property is unset / false, encountering a v2 capture
     * triggers a fail-fast error to avoid silently misinterpreting H2 frames as H1 bytes.
     */
    public static final String H2_SUPPORT_SYSTEM_PROPERTY = "replayer.h2.enabled";

    /**
     * Returns true when the running replayer was built / configured with HTTP/2 capture
     * support enabled. Currently driven by the {@link #H2_SUPPORT_SYSTEM_PROPERTY} system
     * property; will be flipped to default-true after lands.
     */
    public static boolean isH2SupportEnabled() {
        return Boolean.parseBoolean(System.getProperty(H2_SUPPORT_SYSTEM_PROPERTY, "false"));
    }

    /**
     * Validates the {@code captureFormatVersion} envelope field against the running
     * replayer's capabilities. Empty or "v1" always passes. "v2" requires the H2 support
     * flag (see {@link #isH2SupportEnabled()}); otherwise an {@link UnsupportedCaptureFormatException}
     * is thrown with a message pointing at and the toggle.
     *
     * <p>An unrecognized value is treated as a hard failure too: the replayer should not
     * silently accept a future schema it doesn't understand.
     *
     * @param ts the TrafficStream to inspect
     * @throws UnsupportedCaptureFormatException when the capture format is not consumable
     */
    public static void requireSupportedCaptureFormatVersion(TrafficStream ts) {
        var version = ts.getCaptureFormatVersion();
        if (version == null || version.isEmpty() || CAPTURE_FORMAT_VERSION_V1.equals(version)) {
            return;
        }
        if (CAPTURE_FORMAT_VERSION_V2.equals(version)) {
            if (!isH2SupportEnabled()) {
                throw new UnsupportedCaptureFormatException(
                    "TrafficStream connectionId=" + ts.getConnectionId()
                        + " was produced by a v2 (HTTP/2-capable) capture proxy, but this replayer"
                        + " was built without HTTP/2 support. Either upgrade to a replayer with"
                        + " HTTP/2 support, or set -D" + H2_SUPPORT_SYSTEM_PROPERTY + "=true after"
                        + " verifying that the H2 accumulator path is available. See"
                        + " docs/rfcs/0001-http2-trafficcapture.md §5/D5.");
            }
            return;
        }
        throw new UnsupportedCaptureFormatException(
            "TrafficStream connectionId=" + ts.getConnectionId()
                + " has unrecognized captureFormatVersion=\"" + version + "\". Recognized values:"
                + " empty/\"v1\" (legacy H1) or \"v2\" (H1+H2). See docs/rfcs/0001-http2-trafficcapture.md.");
    }

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

    private static String getOptionalContext(TrafficObservation tso) {
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
