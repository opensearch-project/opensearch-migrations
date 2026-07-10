package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;


public interface LuceneLeafReader {

    /**
     * Default {@code position_increment_gap} for analyzed text fields (100). Overridable
     * per-field in the mapping via {@code "position_increment_gap": N}.
     *
     * @see <a href="https://docs.opensearch.org/latest/mappings/mapping-parameters/position-increment-gap/">
     *      OpenSearch position_increment_gap documentation</a>
     */
    int DEFAULT_POSITION_INCREMENT_GAP = 100;

    public LuceneDocument document(int luceneDocId) throws IOException;

    public BitSetConverter.FixedLengthBitSet getLiveDocs();

    public int maxDoc();

    public String getContextString();

    public String getSegmentName();

    public String getSegmentInfoString();

    /**
     * Creates an independent reader view backed by the same underlying segment data.
     * <p>
     * The new view shares the on-disk segment files but maintains its own per-instance
     * mutable caches — most importantly, its own DocValues iterators populated by
     * {@link #initDocValueIterators(Iterable)}. This is the primitive that lets
     * {@link LuceneReader#readDocsFromSegment} run N parallel workers against a single
     * segment, each with its own forward-only iterator cursors, while keeping the
     * per-segment open cost paid only once.
     * <p>
     * Two views over the same underlying reader must be safe to advance concurrently
     * provided each view sees a strictly-ascending docId subsequence (round-robin
     * partitioning satisfies this).
     */
    LuceneLeafReader newView();

    /**
     * Returns field information for all fields with doc_values.
     * Default implementation returns empty iterable for backward compatibility.
     */
    default Iterable<DocValueFieldInfo> getDocValueFields() {
        return Collections.emptyList();
    }

    /**
     * Gets the doc_value for a field at the given document ID.
     */
    default Object getDocValue(int docId, DocValueFieldInfo fieldInfo) throws IOException {
        String fieldName = fieldInfo.name();
        return switch (fieldInfo.docValueType()) {
            case NUMERIC -> getNumericValue(docId, fieldName);
            case SORTED -> getSortedValue(docId, fieldName);
            case SORTED_SET -> getSortedSetValues(docId, fieldName);
            case SORTED_NUMERIC -> getSortedNumericValues(docId, fieldName);
            case BINARY -> getBinaryValue(docId, fieldName);
            case NONE -> null;
        };
    }

    /**
     * Eagerly opens and caches DocValues iterators for the given fields.
     * Subsequent calls to getDocValue/getNumericValue/etc. will use the cached
     * iterators instead of re-acquiring them from the underlying LeafReader.
     * Requires that documents are processed in strictly ascending docId order.
     */
    default void initDocValueIterators(Iterable<DocValueFieldInfo> fields) throws IOException {}


    default Object getNumericValue(int docId, String fieldName) throws IOException { return null; }
    default Object getSortedValue(int docId, String fieldName) throws IOException { return null; }
    default Object getSortedSetValues(int docId, String fieldName) throws IOException { return null; }
    default Object getSortedNumericValues(int docId, String fieldName) throws IOException { return null; }
    default Object getBinaryValue(int docId, String fieldName) throws IOException { return null; }

    /**
     * Gets point values for a field at the given document ID.
     * @return List of byte arrays (packed point values) or null if not available
     */
    default List<byte[]> getPointValues(int docId, String fieldName) throws IOException { return null; }

    /**
     * Gets a field value by scanning the terms index. Very slow for fields with many unique values,
     * but viable for boolean fields (only 2 possible terms: T/F).
     */
    default String getValueFromTerms(int docId, String fieldName) throws IOException { return null; }

    /**
     * Opens a forward-only streaming cursor over the (segment, field) postings, or
     * {@code null} if the field has no positional postings to stream.
     *
     * <p>The streaming cursor walks posting lists via a min-heap of PostingsEnum
     * cursors in a single pass, producing position-ordered {@link TermEntry} lists
     * without any disk spill.
     *
     * <p>The returned cursor caches one PostingsEnum per term and one decoded term
     * string per term — implementations should bound memory by the field's term count
     * (not its posting count). Callers own the lifecycle and must close.
     */
    default StreamingFieldPostings openStreamingFieldPostings(String fieldName) throws IOException {
        return null;
    }

    /**
     * Opens a forward-only streaming cursor over the (segment, field) postings that yields
     * the per-doc multiset of indexed terms (each term repeated by its per-doc frequency)
     * in dictionary order, or {@code null} if the field has no postings to stream.
     *
     * <p>FREQS-only counterpart of {@link #openStreamingFieldPostings(String)}; used to
     * reconstruct multi-valued keyword / not-analyzed subfields whose source representation
     * is an array of duplicates that SORTED_SET doc_values cannot reproduce.
     *
     * <p>Implementations bound memory by the field's unique-term count: one PostingsEnum +
     * one term String per term lives in the heap. Per-segment cost stays O(uniqueTerms)
     * instead of O(docs &times; tokensPerDoc) for the previous eager-map path.
     */
    default StreamingMultiTermPostings openStreamingMultiTermPostings(String fieldName) throws IOException {
        return null;
    }

    /**
     * Version-specific hook: walk the terms dictionary for a trie-encoded numeric field (ES 1.x /
     * Lucene 4-5: long, int, double, float, date, ip) and return a docId -> decoded Long map.
     *
     * Lucene indexes each numeric value as a chain of prefix-coded terms across shift levels
     * (for range query support). Only the {@code shift==0} term carries the fully-precise value;
     * {@code NumericUtils.prefixCodedToLong} / {@code prefixCodedToInt} reverses the encoding.
     * Floats/doubles are stored as {@code sortableFloatBits}/{@code sortableDoubleBits} longs
     * and must be converted by the caller via the mapping type.
     *
     * Returning Long here keeps the interface version-agnostic; the final numeric type
     * (int vs long vs float vs double vs IP string) is applied in {@link SourceReconstructor}
    /**
     * Builds a {@code docId -> Long} map for trie-encoded numeric fields. See callers in
     * {@link SegmentTermIndex#getNumericForDocument} for usage; default returns empty so
     * versions without trie-numerics (Lucene 6+) inherit a no-op.
     *
     * <p>Called at most once per (segment, field) via {@link SegmentTermIndex}.
     */
    default Map<Integer, Long> buildNumericTermIndex(String fieldName) throws IOException {
        return Collections.emptyMap();
    }

    /**
     * Fallback recovery: tries Points (for numerics/IP/date), terms (for boolean),
     * or full term collection (for analyzed strings without stored fields).
     * Used when doc_values and stored fields are not available.
     *
     * <p>The {@link RecoveredValue} variants make the recovery channel explicit so the caller
     * cannot conflate doc_values output (a {@link List} of typed scalars) with point bytes
     * (a {@code List<byte[]>}) — a confusion that previously caused a {@code ClassCastException}
     * on the copy_to reverse-derivation path.
     *
     * @param termIndex per-segment term cache; may be null if the caller does not need
     *                  analyzed-string or numeric-term reconstruction.
     */
    default Optional<RecoveredValue> getValueFromPointsOrTerms(int docId, String fieldName, EsFieldType fieldType,
                                                               SegmentTermIndex termIndex) throws IOException {
        return getValueFromPointsOrTerms(docId, fieldName, fieldType, termIndex, DEFAULT_POSITION_INCREMENT_GAP);
    }

    default Optional<RecoveredValue> getValueFromPointsOrTerms(int docId, String fieldName, EsFieldType fieldType,
                                                               SegmentTermIndex termIndex,
                                                               int positionIncrementGap) throws IOException {
        return switch (fieldType) {
            case BOOLEAN -> {
                String term = getValueFromTerms(docId, fieldName);
                yield term != null ? Optional.of(new RecoveredValue.TextTerm(term)) : Optional.empty();
            }
            case STRING -> {
                if (termIndex != null) {
                    try {
                        List<TermEntry> entries =
                                termIndex.getTermEntriesForDocument(this, docId, fieldName);
                        if (entries != null && !entries.isEmpty()) {
                            List<List<TermEntry>> elements =
                                    splitByPositionGap(entries, positionIncrementGap);
                            if (elements.size() >= 2) {
                                List<String> perElement = new ArrayList<>(elements.size());
                                for (List<TermEntry> bucket : elements) {
                                    perElement.add(joinWithOffsets(bucket));
                                }
                                yield Optional.of(new RecoveredValue.TextTermList(perElement));
                            }
                            yield Optional.of(new RecoveredValue.TextTerm(joinWithOffsets(entries)));
                        }
                    } catch (UnsupportedOperationException e) {
                        // Field indexed without positions; fall through to single-term path.
                    }
                }
                // Multi-valued keyword recovery: use freq-aware term walk to get all values
                // including duplicates. Falls back to single-term for backward compat.
                if (termIndex != null) {
                    List<String> multiTerms = termIndex.getMultiTermsForDocument(this, docId, fieldName);
                    if (multiTerms != null && !multiTerms.isEmpty()) {
                        if (multiTerms.size() == 1) {
                            yield Optional.of(new RecoveredValue.TextTerm(multiTerms.get(0)));
                        }
                        yield Optional.of(new RecoveredValue.TextTermList(multiTerms));
                    }
                }
                String singleTerm;
                if (termIndex != null) {
                    singleTerm = termIndex.getSingleTermForDocument(this, docId, fieldName);
                } else {
                    singleTerm = getValueFromTerms(docId, fieldName);
                }
                yield singleTerm != null
                        ? Optional.of(new RecoveredValue.TextTerm(singleTerm))
                        : Optional.empty();
            }
            case NUMERIC, UNSIGNED_LONG, SCALED_FLOAT, DATE, DATE_NANOS, IP -> {
                // First try Points (Lucene 6+ stores numerics as BKD-tree points).
                List<byte[]> points = getPointValues(docId, fieldName);
                if (points != null && !points.isEmpty()) {
                    yield Optional.of(new RecoveredValue.PointBytes(points));
                }
                // Fall back to trie-encoded terms (Lucene 4-5 / ES 1.x-2.x).
                if (termIndex == null) {
                    yield Optional.empty();
                }
                Long numericVal = termIndex.getNumericForDocument(this, docId, fieldName);
                yield numericVal != null
                        ? Optional.of(new RecoveredValue.NumericTerm(numericVal))
                        : Optional.empty();
            }
            default -> {
                List<byte[]> points = getPointValues(docId, fieldName);
                yield (points != null && !points.isEmpty())
                        ? Optional.of(new RecoveredValue.PointBytes(points))
                        : Optional.empty();
            }
        };
    }

    /**
     * Joins a list of {@link TermEntry} tokens into a single string. Convenience overload that
     * reads the position-gap stopword from {@link RfsTunables#positionGapStopword()}.
     */
    static String joinWithOffsets(List<TermEntry> entries) {
        return joinWithOffsets(entries, RfsTunables.positionGapStopword());
    }

    /**
     * Joins a list of {@link TermEntry} tokens into a single string.
     *
     * <p>When every entry carries valid character offsets (i.e. none equals
     * {@link TermEntry#NO_OFFSET}),
     * the gap between consecutive tokens is preserved exactly: the number of space characters
     * inserted equals {@code entry[i].startOffset() - entry[i-1].endOffset()}. This round-trips
     * the inter-token spacing that the original analyzer saw, including double-spaces.
     *
     * <p>When any entry lacks valid offsets (sentinel -1), the method falls back to a single
     * space between every token — the original behaviour before offset support was added.
     *
     * <p>When {@code positionGapStopword} is non-null AND adjacent entries have a Lucene-position
     * gap greater than 1 (i.e. the source analyzer's stop-word filter ate one or more
     * positions), the stitcher inserts {@code (positionGap - 1)} copies of the token between
     * the two real terms — for example, "i like the tree" indexed by ES with
     * {@code stopwords:["the"]} yields postings [0:i, 1:like, 3:tree], and the reconstructor
     * with {@code positionGapStopword="a"} produces {@code "i like a tree"} so that OS
     * (configured with {@code stopwords:["the","a"]} or just {@code "a"}) re-tokenizes back to
     * the same [0, 1, 3] positions. Without this, the reconstructed string {@code "i like
     * tree"} re-indexes at consecutive [0, 1, 2] and silently changes the slop / proximity
     * semantics of the migrated document. The token MUST be configured as a stopword on the
     * target analyzer for this workaround to round-trip correctly; otherwise it leaks into
     * search results.
     */
    static String joinWithOffsets(List<TermEntry> entries, String positionGapStopword) {
        if (entries.isEmpty()) return "";

        // Same-position graph-token dedup: a tokenizer / token filter can emit several
        // overlapping alternates at the same position (e.g. a custom date analyzer that
        // emits both "03:25:00" and its "03", "25", "00" sub-tokens). For source
        // reconstruction we want the original surface form, so within each position
        // group we keep the longest term. Streaming postings (positional path) does not
        // deduplicate up-front, so we do it here before the offset stitcher sees
        // overlapping spans.
        entries = dedupByLongestAtSamePosition(entries);

        // Check whether all entries carry valid offsets.
        boolean hasOffsets = true;
        for (TermEntry e : entries) {
            if (e.startOffset() < 0 || e.endOffset() < 0) {
                hasOffsets = false;
                break;
            }
        }

        boolean hasStopwordFiller = positionGapStopword != null && !positionGapStopword.isBlank();

        if (!hasOffsets) {
            // Fall back: positions are still available, so inject one stopword filler per
            // missing position when configured; otherwise single-space join (legacy behaviour).
            StringBuilder sb = new StringBuilder();
            int prevPos = -1;
            for (int i = 0; i < entries.size(); i++) {
                TermEntry e = entries.get(i);
                if (i > 0) {
                    sb.append(' ');
                    int posGap = (prevPos >= 0) ? e.position() - prevPos : 1;
                    if (hasStopwordFiller && posGap > 1) {
                        for (int g = 1; g < posGap; g++) sb.append(positionGapStopword).append(' ');
                    }
                }
                sb.append(e.term());
                prevPos = e.position();
            }
            return sb.toString();
        }

        // Offset-gap reconstruction: fill inter-token spans with spaces, optionally splicing
        // a configured stopword for each Lucene position the analyzer's stop-word filter ate.
        StringBuilder sb = new StringBuilder();
        int prevEnd = 0;
        int prevPos = -1;
        for (TermEntry e : entries) {
            int gap = e.startOffset() - prevEnd;
            // gap < 0 can happen only if tokens overlap (e.g. synonym at same position with
            // different lengths after dedup) — clamp to 0 so we never insert negative spaces.
            if (gap > 0) {
                int posGap = (prevPos >= 0) ? e.position() - prevPos : 1;
                if (hasStopwordFiller && posGap > 1) {
                    // Reserve one space immediately after the prior token, then "<token> "
                    // for each missing position. Any remaining offset gap is padded with
                    // spaces afterwards so character offsets that were originally consumed by
                    // a longer stopword (e.g. ES "the" → 3 chars) still align approximately.
                    int fillerLen = positionGapStopword.length();
                    int fillerTokens = posGap - 1;
                    int filledChars = 1 + fillerTokens * (fillerLen + 1);
                    sb.append(' ');
                    for (int t = 0; t < fillerTokens; t++) sb.append(positionGapStopword).append(' ');
                    int remaining = gap - filledChars;
                    for (int s = 0; s < remaining; s++) sb.append(' ');
                } else {
                    for (int s = 0; s < gap; s++) sb.append(' ');
                }
            }
            sb.append(e.term());
            prevEnd = e.endOffset();
            prevPos = e.position();
        }
        return sb.toString();
    }

    /**
     * Within each position group (consecutive entries sharing {@code position()}), keep
     * the entry whose term is longest. Then, walking the kept entries in position order,
     * suppress any entry whose offset span lies entirely within the previously kept
     * entry's span — these are graph-token sub-pieces of an already-emitted longer
     * alternate (e.g. {@code "03"}, {@code "25"}, {@code "00"} after
     * {@code "03:25:00"} is kept).
     *
     * <p>The streaming postings path emits one entry per (term, position) occurrence;
     * tokenizers / token filters that emit overlapping alternates at the same position
     * (synonym graphs, multi-grain date / number tokenizers) therefore produce multiple entries at
     * one position. Without dedup, {@link #joinWithOffsets} would stitch every
     * alternate's bytes back-to-back, corrupting the recovered source.
     */
    static List<TermEntry> dedupByLongestAtSamePosition(List<TermEntry> entries) {
        int n = entries.size();
        if (n < 2) return entries;

        // Fast path: scan once, only allocate if we actually find duplicates.
        // A duplicate is either:
        //   (a) two consecutive entries sharing the same position AND with overlapping
        //       offset spans (a real graph-token alternate); OR both having NO_OFFSET (-1),
        //       in which case same-position alone is sufficient to group and pick longest;
        //   (b) an entry whose offset span is contained within the previous entry's span
        //       (a sub-token at a later position whose long alternate already covered it).
        boolean hasDup = false;
        for (int i = 1; i < n; i++) {
            TermEntry curr = entries.get(i);
            TermEntry prev = entries.get(i - 1);
            if (curr.position() == prev.position()
                    && ((curr.startOffset() >= 0 && prev.startOffset() >= 0
                            && offsetsOverlap(prev, curr))
                        || (curr.startOffset() < 0 && prev.startOffset() < 0))) {
                hasDup = true;
                break;
            }
            if (curr.startOffset() >= 0 && prev.endOffset() >= 0
                    && curr.startOffset() >= prev.startOffset()
                    && curr.endOffset() <= prev.endOffset()) {
                hasDup = true;
                break;
            }
        }
        if (!hasDup) return entries;

        ArrayList<TermEntry> out = new ArrayList<>(n);
        int i = 0;
        while (i < n) {
            // Group consecutive entries at the same position:
            //   - if offsets are available: require overlap (real graph-token alternates)
            //   - if offsets are NO_OFFSET (-1): group by position alone, pick longest
            int j = i + 1;
            while (j < n
                    && entries.get(j).position() == entries.get(i).position()
                    && ((entries.get(i).startOffset() >= 0
                            && entries.get(j).startOffset() >= 0
                            && offsetsOverlap(entries.get(i), entries.get(j)))
                        || (entries.get(i).startOffset() < 0
                            && entries.get(j).startOffset() < 0))) {
                j++;
            }
            // Within [i, j), pick the longest term. Ties broken by encounter order.
            int bestIdx = i;
            int bestLen = entries.get(i).term().length();
            for (int k = i + 1; k < j; k++) {
                int len = entries.get(k).term().length();
                if (len > bestLen) {
                    bestIdx = k;
                    bestLen = len;
                }
            }
            TermEntry winner = entries.get(bestIdx);
            // Suppress winners whose offset span is contained in the prior winner's span
            // (graph sub-token at a later position).
            if (!out.isEmpty()) {
                TermEntry prev = out.get(out.size() - 1);
                if (winner.startOffset() >= 0 && prev.endOffset() >= 0
                        && winner.startOffset() >= prev.startOffset()
                        && winner.endOffset() <= prev.endOffset()) {
                    i = j;
                    continue;
                }
            }
            out.add(winner);
            i = j;
        }
        return out;
    }

    /** True iff {@code a} and {@code b} share at least one character offset. */
    private static boolean offsetsOverlap(TermEntry a, TermEntry b) {
        return a.startOffset() < b.endOffset() && b.startOffset() < a.endOffset();
    }

    /**
     * Splits a position-ordered list of {@link TermEntry} into per-element buckets by
     * detecting position-increment gaps that mark array element boundaries.
     *
     * <p>Within a single multi-valued (array) text field, Lucene assigns increasing
     * positions to tokens. Tokens within the same array element are consecutive (gap of 1).
     * Between successive array elements, Lucene inserts a {@code position_increment_gap}
     * (configurable per-field, default 100) so phrase queries cannot match across elements.
     * A jump of {@code >= positionIncrementGap} between two adjacent entries therefore
     * reliably reveals "the next entry belongs to the next array element."
     *
     * <p>For single-valued fields (or any field whose tokens never exhibit a gap >=
     * the configured threshold), the returned list contains a single bucket equal to the
     * input — the caller can detect this cheaply via {@code result.size() == 1}.
     *
     * @param entries position-ordered tokens for the field
     * @param positionIncrementGap the gap threshold from the field mapping
     * @see <a href="https://docs.opensearch.org/latest/mappings/mapping-parameters/position-increment-gap/">
     *      OpenSearch position_increment_gap documentation</a>
     */
    static List<List<TermEntry>> splitByPositionGap(List<TermEntry> entries, int positionIncrementGap) {
        if (entries.isEmpty()) return Collections.emptyList();
        if (positionIncrementGap <= 0) return List.of(entries);
        List<List<TermEntry>> buckets = new ArrayList<>();
        List<TermEntry> current = new ArrayList<>();
        int prevPos = entries.get(0).position();
        current.add(entries.get(0));
        for (int i = 1; i < entries.size(); i++) {
            TermEntry e = entries.get(i);
            int gap = e.position() - prevPos;
            if (gap >= positionIncrementGap) {
                buckets.add(current);
                current = new ArrayList<>();
            }
            current.add(e);
            prevPos = e.position();
        }
        buckets.add(current);
        return buckets;
    }
}
