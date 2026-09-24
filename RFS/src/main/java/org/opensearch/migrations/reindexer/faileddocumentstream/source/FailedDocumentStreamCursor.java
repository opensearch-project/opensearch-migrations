package org.opensearch.migrations.reindexer.faileddocumentstream.source;

import java.util.Objects;

/**
 * A position within a partition, encoded as {@code fds:1:<ordinal>:<objectKey>}.
 *
 * <p>The key is the string's remainder, not a delimited field, so a key containing {@code :} needs
 * no escaping. The tag makes a cursor from another source fail to parse rather than be misread.
 *
 * <p>Ordinals count records before filtering, so a cursor means the same place whatever the run
 * selected.
 *
 * @param objectKey the object the record came from
 * @param ordinal   zero-based index of that record within the object
 */
public record FailedDocumentStreamCursor(String objectKey, long ordinal) {

    private static final String PREFIX = "fds:1:";

    public FailedDocumentStreamCursor {
        Objects.requireNonNull(objectKey, "objectKey must not be null");
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be >= 0, got " + ordinal);
        }
    }

    public String encode() {
        return PREFIX + ordinal + ":" + objectKey;
    }

    /** Fails on anything this class did not encode. */
    public static FailedDocumentStreamCursor decode(String encoded) {
        if (encoded == null || !encoded.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Not a failure-stream cursor: " + encoded);
        }
        var rest = encoded.substring(PREFIX.length());
        var separator = rest.indexOf(':');
        if (separator < 0) {
            throw new IllegalArgumentException("Failure-stream cursor is missing its object key: " + encoded);
        }
        long ordinal;
        try {
            ordinal = Long.parseLong(rest.substring(0, separator));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Failure-stream cursor has a non-numeric ordinal: " + encoded, e);
        }
        return new FailedDocumentStreamCursor(rest.substring(separator + 1), ordinal);
    }
}
