package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-segment cache of per-field term indexes.
 *
 * Two kinds of indexes are cached here, each lazily built on first access:
 *
 *   1. {@code byField}: fieldName -> docId -> position-ordered terms.
 *      Used by {@link SourceReconstructor} to reconstruct analyzed-text fields
 *      (STRING type) when neither stored fields nor doc_values are available.
 *
 *   2. {@code numericByField}: fieldName -> docId -> decoded Long.
 *      Used by {@link SourceReconstructor} to reconstruct trie-encoded numeric
 *      fields (NUMERIC/IP/DATE/DATE_NANOS/SCALED_FLOAT/UNSIGNED_LONG) when neither
 *      stored fields nor doc_values are available. Lucene 4-5 (ES 1.x/2.x) writes
 *      numerics as chains of prefix-coded terms across shift levels; only
 *      {@code shift==0} terms are harvested and decoded back to longs via
 *      {@link shadow.lucene5.org.apache.lucene.util.NumericUtils} in the
 *      version-specific reader.
 *
 * Lifetime is scoped to a single call to
 * {@link LuceneReader#readDocsFromSegment}: a fresh instance is created there
 * and flows down to {@link SourceReconstructor}. Once the segment's Flux
 * terminates (the outer pipeline's concatMapDelayError has moved on), no live
 * references remain and this object plus its maps become GC-eligible without
 * any explicit clear step.
 *
 * Thread-safety: Lucene TermsEnum / PostingsEnum instances are not safe for
 * concurrent access. Access to the underlying maps is serialized via
 * synchronized to protect the build phase against the per-document concurrent
 * reads within a segment's Flux (see LuceneReader.SEGMENT_READ_CONCURRENCY).
 */
public class SegmentTermIndex {

    private final Map<String, Map<Integer, List<String>>> byField = new HashMap<>();
    private final Map<String, Map<Integer, Long>> numericByField = new HashMap<>();

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

    /**
     * Returns the decoded numeric value (as Long) for {@code docId} in {@code fieldName},
     * building the per-field numeric index on first access. Returns null if the field has no
     * trie-encoded numeric terms or the doc was not indexed with a value for the field.
     *
     * The returned Long is the raw decoded value from the shift==0 term. Interpretation
     * (int vs long vs float vs double vs IP string) is applied downstream via
     * {@link FieldMappingInfo} in {@link SourceReconstructor}.
     */
    public synchronized Long getNumericForDocument(LuceneLeafReader reader, int docId, String fieldName)
            throws IOException {
        Map<Integer, Long> forField = numericByField.get(fieldName);
        if (forField == null) {
            forField = reader.buildNumericTermIndex(fieldName);
            numericByField.put(fieldName, forField);
        }
        return forField.get(docId);
    }
}
