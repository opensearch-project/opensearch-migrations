package org.opensearch.migrations.reindexer.dlq;

import reactor.core.publisher.Mono;

/**
 * Append-only sink for terminal RFS document failures.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #write(DlqRecord)} may buffer; the returned {@link Mono} completes when
 *       the record is durably accepted by the underlying store (post-flush).</li>
 *   <li>{@link #flush()} blocks-equivalent in Reactor terms: completes once all
 *       previously-written records are durable. Must be awaited before the RFS work
 *       coordinator marks the corresponding work item complete.</li>
 *   <li>{@link #close()} flushes and releases resources. Idempotent.</li>
 *   <li>{@link #getLocation()} returns a stable, customer-visible pointer to where
 *       records for this run live (e.g. {@code s3://bucket/prefix/session=&lt;id&gt;/}).</li>
 * </ul>
 *
 * <p>Implementations are responsible for whatever buffering / batching / rotation
 * policy they need. The default {@link S3DlqSink} rotates one S3 object per
 * OpenSearch bulk failure batch.
 */
public interface DlqSink extends AutoCloseable {

    /** Buffer a record. Returned Mono completes only after flush durability. */
    Mono<Void> write(DlqRecord dlqRecord);

    /** Force all buffered records durable. Safe to call repeatedly. */
    Mono<Void> flush();

    /** Customer-visible pointer to the DLQ location for the current session. */
    String getLocation();

    @Override
    void close();
}
