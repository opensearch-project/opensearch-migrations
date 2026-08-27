package org.opensearch.migrations.replay;

/** Thrown when an observation is encountered in a state where a well-formed stream cannot produce it. */
public class MalformedTrafficStreamException extends RuntimeException {
    public MalformedTrafficStreamException(String message) {
        super(message);
    }
}
