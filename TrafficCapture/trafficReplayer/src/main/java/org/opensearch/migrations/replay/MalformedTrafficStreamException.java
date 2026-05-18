package org.opensearch.migrations.replay;

/**
 * Thrown when the accumulator encounters an observation in a state where it
 * cannot occur in a well-formed traffic stream (e.g. an InterimResponse
 * observation outside of the request phase). Failing fast here surfaces
 * capture-side bugs and corrupt data instead of letting them silently
 * produce incorrect replays downstream.
 */
public class MalformedTrafficStreamException extends RuntimeException {
    public MalformedTrafficStreamException(String message) {
        super(message);
    }
}
