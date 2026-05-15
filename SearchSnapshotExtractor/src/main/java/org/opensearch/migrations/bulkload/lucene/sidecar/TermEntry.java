package org.opensearch.migrations.bulkload.lucene.sidecar;

/**
 * A single token with its character-offset span in the original field value.
 *
 * <p>{@code startOffset} and {@code endOffset} are {@link PostingsSink#NO_OFFSET} (-1) when
 * the field was not indexed with {@code index_options: offsets}. Callers must check for -1
 * before using offsets for gap-preserving reconstruction.
 */
public record TermEntry(String term, int startOffset, int endOffset) {}
