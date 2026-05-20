package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * Per-segment cache of per-field term cursors and numeric indexes.
 *
 * <h3>What lives here, and for how long</h3>
 *
 * <p>Three kinds of structures are cached, each lazily built on first access:
 *
 * <ol>
 *   <li><b>{@link #streamingByField}</b>: fieldName &rarr; {@link StreamingFieldPostings}.
 *       Position-aware streaming cursor for analyzed-text fields. Holds one
 *       {@code PostingsEnum} + one term {@code String} per unique term in the field's
 *       dictionary (heap is bounded by {@code uniqueTerms}, not by corpus size).</li>
 *
 *   <li><b>{@link #numericByField}</b>: fieldName &rarr; docId &rarr; decoded {@code Long}.
 *       Eager {@code HashMap} for trie-encoded numeric fields (Lucene 4-5 / ES 1.x-2.x).
 *       Bounded: {@code 16 B × maxDoc} per cached field, e.g. ~3 MB for a 200k-doc segment.</li>
 *
 *   <li><b>{@link #multiTermStreamingByField}</b>: fieldName &rarr;
 *       {@link StreamingMultiTermPostings}. FREQS-only streaming cursor for multi-valued
 *       keyword / not-analyzed subfield recovery. Memory bounded by unique-term count —
 *       replaces the previous eager {@code Map<Integer, List<String>>} that materialized
 *       the entire posting list and OOMed on high-cardinality text segments.</li>
 * </ol>
 *
 * <h3>Heap lifecycle — when entries become unreachable</h3>
 *
 * <ul>
 *   <li><b>Per-field upper bound</b>: each cursor's footprint is O(uniqueTerms) for
 *       streaming maps, O(maxDoc) for numericByField. There is no per-doc accumulator —
 *       emitted {@code List<String>} results are returned to the caller and become
 *       eligible for GC as soon as the caller drops them.</li>
 *   <li><b>Negative caching</b>: {@code put(fieldName, null)} caches the "no postings"
 *       answer to avoid re-walking the dictionary. Cleared at {@link #close()}.</li>
 *   <li><b>Per-segment owner</b>: a fresh instance is created in
 *       {@link LuceneReader#readDocsFromSegment} per segment per worker, and flows down
 *       to {@link SourceReconstructor}. The Flux's {@code doFinally} hook
 *       {@code close()}s every {@code SegmentTermIndex} on success, error, or cancel.</li>
 *   <li><b>No JVM-wide retention</b>: nothing static, nothing in a thread-local. Every
 *       reference path roots to the segment-scoped Flux and dies with it.</li>
 * </ul>
 *
 * <h3>Thread-safety</h3>
 *
 * <p>Lucene {@code TermsEnum} / {@code PostingsEnum} instances are not safe for
 * concurrent access. All public methods are {@code synchronized} to serialize cursor
 * advance and lazy-build operations within a single {@code SegmentTermIndex}. With
 * {@code parallelism > 1}, each worker has its own instance, so contention on this
 * monitor is bounded to the per-worker pipeline.
 */
@Slf4j
public class SegmentTermIndex implements AutoCloseable {

    private final Map<String, StreamingFieldPostings> streamingByField = new HashMap<>();
    private final Map<String, Map<Integer, Long>> numericByField = new HashMap<>();
    private final Map<String, StreamingMultiTermPostings> multiTermStreamingByField = new HashMap<>();
    private volatile boolean closed;

    /** Creates an empty index; fields are populated lazily on first access. */
    public SegmentTermIndex() {
        // No eager work — all caches are demand-built.
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
            streamingByField.put(fieldName, cursor); // may be null — cached as negative answer
        }
        if (cursor != null) {
            return cursor.advance(docId);
        }
        return Collections.emptyList();
    }

    /**
     * Returns the decoded numeric value (as {@code Long}) for {@code docId} in
     * {@code fieldName}, building the per-field numeric index on first access. Returns
     * {@code null} if the field has no trie-encoded numeric terms or the doc was not
     * indexed with a value.
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
     * Returns the single decoded term string for {@code docId} in {@code fieldName} via
     * the streaming multi-term cursor, taking the first emitted term. Returns {@code null}
     * if the field has no terms or the doc was not indexed with a value.
     *
     * <p>Replaces the previous eager {@code docId -> term} map. Streaming bounds memory by
     * the field's unique-term count rather than its document count.
     *
     * <p>Note: the streaming cursor requires monotonically non-decreasing docIds. The
     * caller must access docs in ascending order — this matches the per-segment Flux
     * ordering. Regressions throw {@link IllegalStateException} from the cursor.
     */
    public synchronized String getSingleTermForDocument(LuceneLeafReader reader, int docId, String fieldName)
            throws IOException {
        List<String> all = getMultiTermsForDocument(reader, docId, fieldName);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Returns ALL terms for {@code docId} in {@code fieldName}, each repeated by its
     * per-doc frequency. For multi-valued keyword fields (object-array subfields), this
     * recovers the full multiset of values including duplicates — unlike SORTED_SET
     * doc_values which deduplicates, or {@link #getSingleTermForDocument} which returns
     * only one.
     *
     * <p>Order across distinct terms is unspecified (heap-internal); callers must
     * consume the result as a multiset. The total count matches the original array
     * size, enabling exact-size distribution.
     *
     * <p>Backed by {@link StreamingMultiTermPostings}: terms are decoded once at build
     * time and reused via reference equality across docs and across the {@code freq}
     * repetitions per doc, so memory is bounded by unique-term count, not corpus size.
     */
    public synchronized List<String> getMultiTermsForDocument(LuceneLeafReader reader, int docId, String fieldName)
            throws IOException {
        if (closed) {
            throw new IOException("SegmentTermIndex has been closed");
        }
        StreamingMultiTermPostings cursor = multiTermStreamingByField.get(fieldName);
        if (cursor == null && !multiTermStreamingByField.containsKey(fieldName)) {
            cursor = reader.openStreamingMultiTermPostings(fieldName);
            multiTermStreamingByField.put(fieldName, cursor); // may be null — cached as negative answer
        }
        if (cursor == null) {
            return Collections.emptyList();
        }
        return cursor.advance(docId);
    }

    /**
     * Closes every streaming cursor and clears all caches.
     * Safe to call multiple times. Exceptions from individual closes are logged and
     * swallowed so one failing field doesn't leak the others.
     *
     * <p>After this returns, the maps hold no references and become eligible for GC
     * along with the {@code SegmentTermIndex} once its caller releases it.
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
        for (Map.Entry<String, StreamingMultiTermPostings> e : multiTermStreamingByField.entrySet()) {
            StreamingMultiTermPostings cursor = e.getValue();
            if (cursor == null) continue;
            try {
                cursor.close();
            } catch (Exception ex) {
                log.warn("Failed to close streaming multi-term postings for field {}: {}",
                        e.getKey(), ex.toString());
            }
        }
        streamingByField.clear();
        numericByField.clear();
        multiTermStreamingByField.clear();
    }
}
