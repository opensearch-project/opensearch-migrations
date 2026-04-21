package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-segment cache of (fieldName -> docId -> position-ordered terms).
 *
 * Built lazily on first access for each field by delegating to the reader's
 * {@link LuceneLeafReader#buildTermPositionIndex(String)}, which performs a
 * single-pass walk of the terms dictionary (O(terms * docs_per_term)).
 *
 * Lifetime is scoped to a single call to
 * {@link LuceneReader#readDocsFromSegment}: a fresh instance is created there
 * and flows down to {@link SourceReconstructor}. Once the segment's Flux
 * terminates (the outer pipeline's concatMapDelayError has moved on), no live
 * references remain and this object plus its maps become GC-eligible without
 * any explicit clear step.
 *
 * Thread-safety: Lucene TermsEnum / PostingsEnum instances are not safe for
 * concurrent access. Access to the underlying map is serialized via
 * synchronized to protect the build phase against the per-document concurrent
 * reads within a segment's Flux (see LuceneReader.SEGMENT_READ_CONCURRENCY).
 */
public class SegmentTermIndex {

    private final Map<String, Map<Integer, List<String>>> byField = new HashMap<>();

    /**
     * Returns the position-ordered list of indexed terms for {@code docId} in
     * {@code fieldName}, building the per-field index on first access.
     */
    public synchronized List<String> getTermsForDocument(LuceneLeafReader reader, int docId, String fieldName)
            throws IOException {
        Map<Integer, List<String>> forField = byField.get(fieldName);
        if (forField == null) {
            forField = reader.buildTermPositionIndex(fieldName);
            byField.put(fieldName, forField);
        }
        return forField.getOrDefault(docId, Collections.emptyList());
    }
}
