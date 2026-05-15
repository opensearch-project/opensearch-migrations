package org.opensearch.migrations.bulkload.lucene.sidecar;

import java.io.IOException;

/**
 * Version-agnostic sink that receives {@code (termId, docId, positions[])} triples emitted
 * by a per-Lucene-version reader's postings walk. Implementers are responsible for turning
 * the term-major, docId-ascending input stream into a doc-major, position-ascending sidecar.
 *
 * <p>Contract the emitter (a {@code LuceneLeafReader.streamFieldPostings} implementation)
 * MUST honor:
 *
 * <ol>
 *   <li>Call {@link #registerTerm(BytesRefLike)} exactly once per distinct term, in
 *       {@code TermsEnum.next()} order (ascending sorted bytes). The returned {@code int}
 *       is the termId the emitter will use on subsequent {@link #accept} calls.
 *   <li>For each term, iterate its postings in ascending docId order (Lucene's
 *       {@code PostingsEnum} natural contract). Call {@link #accept} once per (term, doc)
 *       with the doc's positions array and the count of valid entries.
 *   <li>Positions within a (term, doc) MUST be ascending and non-negative. Tuples emitted
 *       for fields without positional indexing (DOCS_ONLY, DOCS_AND_FREQS) MUST skip any
 *       sentinel position value the reader receives from Lucene ({@code &lt; 0}).
 * </ol>
 */
public interface PostingsSink {

    /**
     * Sentinel value for start/end character offsets when the field was not indexed with
     * {@code index_options: offsets} (i.e., Lucene's {@code PostingsEnum.startOffset()} /
     * {@code endOffset()} return -1). Stored as-is in the sidecar; callers check for -1
     * before attempting offset-based gap reconstruction.
     */
    int NO_OFFSET = -1;

    /**
     * Registers a distinct term. Called exactly once per term, in ascending sorted-bytes
     * order. The returned int is the small, dense termId the sink will accept on subsequent
     * {@link #accept} calls.
     */
    int registerTerm(BytesRefLike term) throws IOException;

    /**
     * Records {@code positionCount} positions at which {@code termId} occurs in {@code docId},
     * with per-occurrence character start/end offsets.
     *
     * <p>The {@code positions}, {@code startOffsets}, and {@code endOffsets} arrays are owned by
     * the caller and may be reused across calls — sinks MUST consume the valid prefix
     * {@code [0, positionCount)} during this call and not retain any array reference.
     *
     * <p>When the field was not indexed with character offsets, pass {@link #NO_OFFSET} (-1)
     * for every element of {@code startOffsets} and {@code endOffsets}.
     */
    void accept(int termId, int docId, int[] positions, int[] startOffsets, int[] endOffsets, int positionCount) throws IOException;

    /**
     * Convenience overload for callers that do not have character-offset information.
     * Delegates to {@link #accept(int, int, int[], int[], int[], int)} with {@link #NO_OFFSET}
     * filled for every offset slot.
     */
    default void accept(int termId, int docId, int[] positions, int positionCount) throws IOException {
        int[] noOffsets = new int[positionCount];
        java.util.Arrays.fill(noOffsets, NO_OFFSET);
        accept(termId, docId, positions, noOffsets, noOffsets, positionCount);
    }
}
