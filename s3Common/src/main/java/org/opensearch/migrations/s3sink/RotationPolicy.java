package org.opensearch.migrations.s3sink;

import java.time.Duration;
import java.time.Instant;

/**
 * When a {@link RotatingGzipS3ObjectWriter} should close its current object and start a new one.
 *
 * <p>Any dimension can be disabled: a non-positive {@code maxUncompressedBytes}/{@code maxRecords}
 * or a null {@code maxAge} means "never rotate on that dimension". A writer that disables all three
 * only ever produces a new object on an explicit {@link RotatingGzipS3ObjectWriter#flush()}.
 */
public record RotationPolicy(long maxUncompressedBytes, Duration maxAge, long maxRecords) {

    /** Rotate only when the uncompressed buffer crosses {@code maxUncompressedBytes} (no age/count). */
    public static RotationPolicy ofBytes(long maxUncompressedBytes) {
        return new RotationPolicy(maxUncompressedBytes, null, 0);
    }

    boolean shouldRotate(long uncompressedBytes, long records, Instant openedAt, Instant now) {
        if (maxUncompressedBytes > 0 && uncompressedBytes >= maxUncompressedBytes) {
            return true;
        }
        if (maxRecords > 0 && records >= maxRecords) {
            return true;
        }
        return isAged(openedAt, now);
    }

    boolean isAged(Instant openedAt, Instant now) {
        return maxAge != null && Duration.between(openedAt, now).compareTo(maxAge) >= 0;
    }
}
