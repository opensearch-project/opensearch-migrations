package org.opensearch.migrations.trafficcapture.protos;

/**
 * Thrown when a {@link TrafficStream} carries a {@code captureFormatVersion} that the running
 * replayer cannot safely consume — either an unrecognized future version, or v2 (HTTP/2-capable)
 * when the replayer was built without HTTP/2 support.
 *
 * <p>Per (load-bearing compatibility rule 5): an H2-unaware replayer must fail
 * fast on v2 captures rather than silently misinterpreting H2 frames as H1 bytes.
 */
public class UnsupportedCaptureFormatException extends RuntimeException {
    public UnsupportedCaptureFormatException(String message) {
        super(message);
    }
}
