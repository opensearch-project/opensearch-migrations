package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * Per-segment cache of per-field term indexes.
 *
 * <p>Three kinds of indexes are cached here, each lazily built on first access:
 *
 * <ol>
 *   <li><b>streamingByField</b>: fieldName -&gt; {@link StreamingFieldPostings}.
 *       Used by {@link SourceReconstructor} to reconstruct analyzed-text fields
 *       (STRING type) when neither stored fields nor doc_values are available.
 *       The streaming path walks posting lists via a min-heap of PostingsEnum
 *       cursors in a single pass, producing position-ordered {@link TermEntry}
 *       lists without any disk spill.</li>
 *
 *   <li><b>numericByField</b>: fieldName -&gt; docId -&gt; decoded Long.
 *       Used to reconstruct trie-encoded numeric fields
 *       (NUMERIC/IP/DATE/DATE_NANOS/SCALED_FLOAT/UNSIGNED_LONG) in Lucene 4/5
 *       segments. Stays in heap: 16 bytes per (docId, Long) entry times maxDoc
 *       is bounded and small (a 200k-doc segment is ~3 MB of numeric index).</li>
 *
 *   <li><b>singleTermByField / multiTermByField</b>: keyword/not-analyzed field
 *       recovery caches.</li>
 * </ol>
 *
 * <p>Lifetime is scoped to a single call to
 * {@link LuceneReader#readDocsFromSegment}: a fresh instance is created there,
 * and flows down to {@link SourceReconstructor}. Once the segment's Flux
 * terminates (success, error, or cancel), the Flux's {@code doFinally} hook
 * calls {@link #close()}, which closes all open streaming cursors.
 *
 * <p>Thread-safety: Lucene TermsEnum / PostingsEnum instances are not safe for
 * concurrent access. Access to the underlying maps and build phase is serialized
 * via {@code synchronized} to protect against the per-document concurrent reads
 * within a segment's Flux (see {@link LuceneReader}).
 */
@Slf4j
public class SegmentTermIndex implements AutoCloseable {

    private final Map<String, StreamingFieldPostings> streamingByField = new HashMap<>();
    private final Map<String, Map<Integer, Long>> numericByField = new HashMap<>();
    private final Map<String, Map<Integer, String>> singleTermByField = new HashMap<>();
    private final Map<String, Map<Integer, List<String>>> multiTermByField = new HashMap<>();
    private volatile boolean closed;

    /**
     * Creates a no-arg index. The streaming path is always used for text recovery.
     */
    public SegmentTermIndex() {
    }

    /**
     * Returns the position-ordered list of indexed terms for {@code docId} in
     * {@code fieldName}, building the per-field streaming cursor on first access.
     */
    public synchronized List<String> getTermsForDocument(LuceneLeafReader reader, int docId, String fieldName)
            throws IOException {
        List<TermEntry> entries = getTermEntriesForDocument(reader, docId, fieldName);
        List<String> strings = new ArrayList<>(entries.size());
        for (TermEntry e : entries) strings.add(e.term());
        return strings;
    }

    /**
     * Returns the {@link TermEntry} list for {@code docId} in {@code fieldName},
     * including character start/end offsets when the field was indexed with
     * {@code index_options: offsets}. Opens a streaming cursor on first access.
     */
    public synchronized List<TermEntry> getTermEntriesForDocument(
            LuceneLeafReader reader, int docId, String fieldName) throws IOException {
        if (closed) {
            throw new IOException("SegmentTermIndex has been closed");
        }
        StreamingFieldPostings cursor = streamingByField.get(fieldName);
        if (cursor == null && !streamingByField.containsKey(fieldName)) {
            cursor = reader.openStreamingFieldPostings(fieldName);
            streamingByField.put(fieldName, cursor); // may be null, which we cache
        }
        if (cursor != null) {
            return cursor.advance(docId);
        }
        return Collections.emptyList();
    }

    /**
     * Returns the decoded numeric value (as Long) for {@code docId} in {@code fieldName},
     * building the per-field numeric index on first access. Returns null if the field has no
     * trie-encoded numeric terms or the doc was not indexed with a value for the field.
     *
     * <p>The returned Long is the raw decoded value from the shift==0 term. Interpretation
     * (int vs long vs float vs double vs IP string) is applied downstream via
     * {@link FieldMappingInfo} in {@link SourceReconstructor}.
     */
    public synchronized Long getNumericForDocument(LuceneLeafReader reader, int docId, String fieldName)
            throws IOException {
        if (closed) {
            throw new IOException("SegmentTermIndex has been closed");
        }
        Map<Integer, Long> forField = numericByField.get(fieldName);
        if (forField == null) {
            forField = reader.buildNumericTermIndex(fieldName);
            numericByField.put(fieldName, forField);
        }
        return forField.get(docId);
    }

    /**
     * Returns the single decoded term string for {@code docId} in {@code fieldName},
     * building the per-field {@code docId -> term} map on first access. Returns null
     * if the field has no terms or the doc was not indexed with a value for the field.
     *
     * <p>This avoids the O(numTerms) per-doc linear scan in
     * {@link LuceneLeafReader#getValueFromTerms(int, String)} for keyword / not-analyzed
     * fields, which is the dominant cost when many docs need the same field reconstructed.
     */
    public synchronized String getSingleTermForDocument(LuceneLeafReader reader, int docId, String fieldName)
            throws IOException {
        if (closed) {
            throw new IOException("SegmentTermIndex has been closed");
        }
        Map<Integer, String> forField = singleTermByField.get(fieldName);
        if (forField == null) {
            forField = reader.buildSingleTermIndex(fieldName);
            singleTermByField.put(fieldName, forField);
        }
        return forField.get(docId);
    }

    /**
     * Returns ALL terms for {@code docId} in {@code fieldName}, each repeated by its
     * per-doc frequency. For multi-valued keyword fields (object-array subfields), this
     * recovers the full multiset of values including duplicates — unlike SORTED_SET
     * doc_values which deduplicates, or getSingleTermForDocument which returns only one.
     *
     * <p>Order is dictionary order (not insertion order), so positional binding to specific
     * array elements is not guaranteed. However, the total count matches the original array
     * size, enabling exact-size distribution.
     */
    public synchronized List<String> getMultiTermsForDocument(LuceneLeafReader reader, int docId, String fieldName)
            throws IOException {
        if (closed) {
            throw new IOException("SegmentTermIndex has been closed");
        }
        Map<Integer, List<String>> forField = multiTermByField.get(fieldName);
        if (forField == null) {
            forField = reader.buildMultiTermIndex(fieldName);
            multiTermByField.put(fieldName, forField);
        }
        return forField.get(docId);
    }

    /**
     * Closes every streaming cursor and clears all caches.
     * Safe to call multiple times. Exceptions from individual closes are logged
     * and swallowed so one failing field doesn't leak the rest.
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Map.Entry<String, StreamingFieldPostings> e : streamingByField.entrySet()) {
            StreamingFieldPostings cursor = e.getValue();
            if (cursor == null) continue;
            try {
                cursor.close();
            } catch (Exception ex) {
                log.warn("Failed to close streaming postings for field {}: {}", e.getKey(), ex.toString());
            }
        }
        streamingByField.clear();
        numericByField.clear();
        singleTermByField.clear();
        multiTermByField.clear();
    }
}
