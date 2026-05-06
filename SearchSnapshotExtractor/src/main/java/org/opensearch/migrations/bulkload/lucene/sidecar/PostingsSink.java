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
     * Registers a distinct term. Called exactly once per term, in ascending sorted-bytes
     * order. The returned int is the small, dense termId the sink will accept on subsequent
     * {@link #accept} calls.
     */
    int registerTerm(BytesRefLike term) throws IOException;

    /**
     * Records {@code positionCount} positions at which {@code termId} occurs in {@code docId}.
     * The {@code positions} array is owned by the caller and may be reused across calls —
     * sinks MUST consume the valid prefix {@code [0, positionCount)} during this call and
     * not retain the array reference.
     */
    void accept(int termId, int docId, int[] positions, int positionCount) throws IOException;
}
