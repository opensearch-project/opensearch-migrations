package org.opensearch.migrations.bulkload.pipeline.model;

import java.util.List;
import java.util.Objects;

/** Additive operation counts, request costs and terminal failures for a contiguous range of completed batches. */
public record MigrationMetrics(Operations source, Operations target, Requests requests, Failures failures) {
    public static final MigrationMetrics ZERO =
        new MigrationMetrics(Operations.ZERO, Operations.ZERO, Requests.ZERO, Failures.ZERO);

    public MigrationMetrics {
        Objects.requireNonNull(source);
        Objects.requireNonNull(target);
        Objects.requireNonNull(requests);
        Objects.requireNonNull(failures);
    }

    public MigrationMetrics(Operations source, Operations target, Requests requests) {
        this(source, target, requests, Failures.ZERO);
    }

    public MigrationMetrics plus(MigrationMetrics other) {
        return new MigrationMetrics(source.plus(other.source), target.plus(other.target), requests.plus(other.requests),
            failures.plus(other.failures));
    }

    public record Operations(long upserts, long deletes, long bytes) {
        public static final Operations ZERO = new Operations(0, 0, 0);

        public Operations {
            if (upserts < 0 || deletes < 0 || bytes < 0) {
                throw new IllegalArgumentException("Operation totals must be nonnegative");
            }
        }

        public static Operations fromDocuments(List<Document> documents) {
            long upserts = 0;
            long bytes = 0;
            for (var document : documents) {
                if (document.operation() == Document.Operation.UPSERT) {
                    upserts++;
                    bytes += document.sourceLength();
                }
            }
            return new Operations(upserts, documents.size() - upserts, bytes);
        }

        public Operations plus(Operations other) {
            return new Operations(Math.addExact(upserts, other.upserts), Math.addExact(deletes, other.deletes),
                Math.addExact(bytes, other.bytes));
        }
    }

    /** HTTP attempts for completed logical batches, including retries; bytes are uncompressed NDJSON. */
    public record Requests(long batches, long total, long retries, long bytes, long durationMillis) {
        public static final Requests ZERO = new Requests(0, 0, 0, 0, 0);

        public Requests {
            if (batches < 0 || total < 0 || retries < 0 || bytes < 0 || durationMillis < 0) {
                throw new IllegalArgumentException("Request totals must be nonnegative");
            }
        }

        public Requests plus(Requests other) {
            return new Requests(Math.addExact(batches, other.batches), Math.addExact(total, other.total),
                Math.addExact(retries, other.retries),
                Math.addExact(bytes, other.bytes), Math.addExact(durationMillis, other.durationMillis));
        }
    }

    /**
     * Target operations that became terminal failures, by failure class: each is, or with the failed document stream
     * disabled would be, one failed-document record.
     */
    public record Failures(long nonRetryable, long retryableExhausted) {
        public static final Failures ZERO = new Failures(0, 0);

        public Failures {
            if (nonRetryable < 0 || retryableExhausted < 0) {
                throw new IllegalArgumentException("Failure totals must be nonnegative");
            }
        }

        public long total() {
            return Math.addExact(nonRetryable, retryableExhausted);
        }

        public Failures plus(Failures other) {
            return new Failures(Math.addExact(nonRetryable, other.nonRetryable),
                Math.addExact(retryableExhausted, other.retryableExhausted));
        }
    }
}
