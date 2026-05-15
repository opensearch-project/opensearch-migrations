package org.opensearch.migrations.bulkload.lucene.sidecar;

/**
 * A single token with its position and character-offset span in the original field value.
 *
 * <p>{@code position} is the absolute Lucene position emitted by {@code PostingsEnum.nextPosition()}.
 * It is monotonically increasing within a single (doc, field). For multi-valued (array) fields,
 * Lucene inserts a {@code position_increment_gap} (default 100) between successive array elements,
 * so a sudden jump in position between adjacent {@code TermEntry}s reveals an array element
 * boundary. Single-valued fields have positions that increase by 1 per token.
 *
 * <p>{@code startOffset} and {@code endOffset} are {@link PostingsSink#NO_OFFSET} (-1) when
 * the field was not indexed with {@code index_options: offsets}. Callers must check for -1
 * before using offsets for gap-preserving reconstruction.
 */
public record TermEntry(String term, int position, int startOffset, int endOffset) {}
