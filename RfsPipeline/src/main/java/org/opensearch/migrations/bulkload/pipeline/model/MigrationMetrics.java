package org.opensearch.migrations.bulkload.pipeline.model;

import java.util.List;
import java.util.Objects;

/** Additive costs and source operations belonging to a contiguous range of completed batches. */
public record MigrationMetrics(Source source, Requests requests) {
    public static final MigrationMetrics ZERO = new MigrationMetrics(Source.ZERO, Requests.ZERO);

    public MigrationMetrics {
        Objects.requireNonNull(source);
        Objects.requireNonNull(requests);
    }

    public MigrationMetrics plus(MigrationMetrics other) {
        return new MigrationMetrics(source.plus(other.source), requests.plus(other.requests));
    }

    public record Source(long upserts, long deletes, long bytes) {
        public static final Source ZERO = new Source(0, 0, 0);

        public Source {
            if (upserts < 0 || deletes < 0 || bytes < 0) {
                throw new IllegalArgumentException("Source totals must be nonnegative");
            }
        }

        public static Source fromDocuments(List<Document> documents) {
            long upserts = 0;
            long bytes = 0;
            for (var document : documents) {
                if (document.operation() == Document.Operation.UPSERT) {
                    upserts++;
                    bytes += document.sourceLength();
                }
            }
            return new Source(upserts, documents.size() - upserts, bytes);
        }

        public Source plus(Source other) {
            return new Source(Math.addExact(upserts, other.upserts), Math.addExact(deletes, other.deletes),
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
}
