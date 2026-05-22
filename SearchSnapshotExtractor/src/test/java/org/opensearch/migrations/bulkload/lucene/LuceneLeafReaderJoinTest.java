package org.opensearch.migrations.bulkload.lucene;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for {@link LuceneLeafReader#joinWithOffsets} and
 * {@link LuceneLeafReader#dedupByLongestAtSamePosition}.
 *
 * <p>The most important case here is the customer regression where a multi-grain
 * tokenizer (e.g. a custom multi-grain string tokenizer) emitted overlapping graph
 * tokens at the same Lucene position for date-like spans like
 * {@code "2001 03:25:00"}. Without same-position dedup the streaming postings
 * path stitched every alternate's bytes back-to-back, producing strings like
 * {@code "...03:2001:03:25:00:0800:00..."} where the recovered subject was
 * supposed to be {@code "...2001 03:25:00 -0800..."}.
 */
class LuceneLeafReaderJoinTest {

    private static TermEntry te(String term, int pos, int start, int end) {
        return new TermEntry(term, pos, start, end);
    }

    private static TermEntry teNoOffset(String term, int pos) {
        return new TermEntry(term, pos, TermEntry.NO_OFFSET, TermEntry.NO_OFFSET);
    }

    // -------------------------------------------------------------------------
    //  joinWithOffsets baseline behaviour (unchanged by the dedup fix)
    // -------------------------------------------------------------------------

    @Test
    void joinWithOffsets_emptyList_returnsEmptyString() {
        assertEquals("", LuceneLeafReader.joinWithOffsets(List.of()));
    }

    @Test
    void joinWithOffsets_noOffsetSentinel_fallsBackToSingleSpaceJoin() {
        // When any entry has NO_OFFSET we lose the ability to preserve gaps;
        // the code falls back to single-space join so the result is at least readable.
        String stitched = LuceneLeafReader.joinWithOffsets(List.of(
            teNoOffset("hello", 0),
            teNoOffset("world", 1)
        ));
        assertEquals("hello world", stitched);
    }

    @Test
    void joinWithOffsets_preservesInterTokenSpacing() {
        // "date  wed" with double space between tokens — offsets capture the gap exactly.
        //  d  a  t  e        w  e  d
        //  0  1  2  3  4  5  6  7  8  9
        //  ^________^        ^________^
        //  start=0 end=4     start=6 end=9
        String stitched = LuceneLeafReader.joinWithOffsets(List.of(
            te("date", 0, 0, 4),
            te("wed",  1, 6, 9)
        ));
        assertEquals("date  wed", stitched);
    }

    @Test
    void joinWithOffsets_singleToken_returnsTermVerbatim() {
        String stitched = LuceneLeafReader.joinWithOffsets(List.of(te("solo", 0, 5, 9)));
        // Leading 5 chars of left padding from the source are preserved as spaces.
        assertEquals("     solo", stitched);
    }

    // -------------------------------------------------------------------------
    //  dedupByLongestAtSamePosition contract
    // -------------------------------------------------------------------------

    @Test
    void dedup_noDuplicates_returnsInputReference() {
        List<TermEntry> input = List.of(
            te("a", 0, 0, 1),
            te("b", 1, 2, 3),
            te("c", 2, 4, 5)
        );
        // Fast path: no duplicates means the dedup helper hands the original list back.
        assertSame(input, LuceneLeafReader.dedupByLongestAtSamePosition(input));
    }

    @Test
    void dedup_samePositionGroup_keepsLongestTerm() {
        // Three alternates at position=5 with overlapping offsets: "03" (4..6), "03:25:00"
        // (4..12), "0325" (4..8). All overlap on the 4..6 prefix, so they form one graph
        // alternate set. Longest term wins.
        List<TermEntry> input = List.of(
            te("foo",       4, 0, 3),
            te("03",        5, 4, 6),
            te("03:25:00",  5, 4, 12),
            te("0325",      5, 4, 8),
            te("bar",       6, 13, 16)
        );

        List<TermEntry> out = LuceneLeafReader.dedupByLongestAtSamePosition(input);
        assertEquals(3, out.size());
        assertEquals("foo",      out.get(0).term());
        assertEquals("03:25:00", out.get(1).term());
        assertEquals(5,          out.get(1).position());
        // Winner's offsets are kept (4 → 12, the long span)
        assertEquals(4,          out.get(1).startOffset());
        assertEquals(12,         out.get(1).endOffset());
        assertEquals("bar",      out.get(2).term());
    }

    @Test
    void dedup_singleEntry_returnsAsIs() {
        List<TermEntry> input = List.of(te("only", 0, 0, 4));
        assertSame(input, LuceneLeafReader.dedupByLongestAtSamePosition(input));
    }

    @Test
    void dedup_tieOnLength_keepsFirstEncountered() {
        // Two same-length alternates at same position with overlapping offsets:
        // stable ordering keeps the first.
        List<TermEntry> input = List.of(
            te("aaa", 0, 0, 3),
            te("bbb", 0, 0, 3)
        );
        List<TermEntry> out = LuceneLeafReader.dedupByLongestAtSamePosition(input);
        assertEquals(1, out.size());
        assertEquals("aaa", out.get(0).term());
    }

    // -------------------------------------------------------------------------
    //  Customer regression: multi-grain tokenizer with overlapping date tokens
    // -------------------------------------------------------------------------

    @Test
    void joinWithOffsets_overlappingDateTokensAtSamePosition_dedupsToLongestSurfaceForm() {
        // Reproduces the customer failure on a `subj` field analyzed with a multi-grain
        // tokenizer that emits overlapping alternates for "2001 03:25:00 -0800":
        //   pos=0: "2001"          offsets 0..4
        //   pos=1: "03"            offsets 5..7
        //   pos=1: "03:25:00"      offsets 5..13   (graph alternate covering the whole span)
        //   pos=2: "25"            offsets 8..10
        //   pos=3: "00"            offsets 11..13
        //   pos=4: "-0800"         offsets 14..19
        //
        // The streaming path emits ALL of these because PostingsEnum.freq() reflects every
        // term-occurrence; without dedup the offset stitcher produces back-to-back garbage.
        List<TermEntry> entries = List.of(
            te("2001",     0, 0, 4),
            te("03",       1, 5, 7),
            te("03:25:00", 1, 5, 13),
            te("25",       2, 8, 10),
            te("00",       3, 11, 13),
            te("-0800",    4, 14, 19)
        );

        // After dedup at the same-position step: kept = [2001 / 03:25:00 / 25 / 00 / -0800].
        // The sub-token suppression step then drops 25 (offsets 8..10 ⊆ 5..13) and 00
        // (offsets 11..13 ⊆ 5..13) since they're inside the kept 03:25:00 span. Final
        // kept entries: [2001@0..4, 03:25:00@5..13, -0800@14..19].
        String stitched = LuceneLeafReader.joinWithOffsets(entries);

        // The recovered subject is the longest alternate plus the trailing token,
        // with original gap-spaces preserved. NO back-to-back "032500..." garbage,
        // and no leftover sub-token duplication.
        assertEquals("2001 03:25:00 -0800", stitched);
    }

    @Test
    void joinWithOffsets_singletonGroupsOnly_unchangedBehaviour() {
        // Sanity check: when there's no overlap, dedup is a no-op and stitching matches
        // the pre-fix output exactly.
        List<TermEntry> entries = List.of(
            te("date", 0, 0, 4),
            te("wed",  1, 6, 9),
            te("14",   2, 12, 14),
            te("mar",  3, 15, 18),
            te("2001", 4, 19, 23)
        );

        String stitched = LuceneLeafReader.joinWithOffsets(entries);
        assertEquals("date  wed   14 mar 2001", stitched);
    }

    @Test
    void dedup_runsOfDuplicatesAtMultiplePositions() {
        // Two separate same-position groups, each with overlapping offsets within
        // the group. Longest in each group wins.
        List<TermEntry> input = List.of(
            te("a",   0, 0, 1),
            te("aa",  0, 0, 2),
            te("b",   1, 3, 4),
            te("bb",  1, 3, 5),
            te("bbb", 1, 3, 6),
            te("c",   2, 7, 8)
        );

        List<TermEntry> out = LuceneLeafReader.dedupByLongestAtSamePosition(input);
        assertEquals(3, out.size());
        assertEquals("aa",  out.get(0).term());
        assertEquals("bbb", out.get(1).term());
        assertEquals("c",   out.get(2).term());
    }

    @Test
    void dedup_samePositionAllNoOffset_keepsLongest() {
        // When offsets are NO_OFFSET (-1), same-position alone is sufficient to group.
        // The longest term wins, matching the offset-aware behaviour.
        List<TermEntry> input = List.of(
            teNoOffset("a",   0),
            teNoOffset("aa",  0),
            teNoOffset("aaa", 0)
        );
        List<TermEntry> out = LuceneLeafReader.dedupByLongestAtSamePosition(input);
        assertEquals(1, out.size());
        assertEquals("aaa", out.get(0).term());
    }

    @Test
    void dedup_samePositionWithoutOffsetOverlap_isPreserved() {
        // Defensive: same-position entries that DON'T share offset spans cannot be real
        // graph-token alternates from Lucene postings. Don't drop them — they may be
        // synthetic test fixtures or future analyzer shapes we haven't seen.
        List<TermEntry> input = List.of(
            te("date", 0, 0,  4),
            te("tue",  0, 6,  9),
            te("26",   0, 11, 13)
        );
        // No overlap → no dedup → same reference returned.
        assertSame(input, LuceneLeafReader.dedupByLongestAtSamePosition(input));
    }

    // -------------------------------------------------------------------------
    //  Position-gap stopword filler (preserves ES position increments through
    //  reconstruction so OS re-tokenization keeps the same slop / phrase semantics)
    // -------------------------------------------------------------------------

    @Test
    void joinWithOffsets_noOffsets_positionGap_insertsStopwordFiller() {
        // ES indexed "i like the tree" with stopword "the" — postings carry [0:i, 1:like, 3:tree].
        // Position 2 is consumed by "the" but the term is dropped, leaving a gap. The reconstructor
        // splices the configured stopword token so OS re-creates the same gap.
        List<TermEntry> entries = List.of(
            teNoOffset("i",    0),
            teNoOffset("like", 1),
            teNoOffset("tree", 3)
        );
        assertEquals("i like a tree", LuceneLeafReader.joinWithOffsets(entries, "a"));
    }

    @Test
    void joinWithOffsets_noOffsets_multiplePositionsSkipped_insertsMultipleFillers() {
        // Two consecutive stopwords ate positions 1 and 2; reconstructor inserts two fillers
        // so OS reproduces the exact 0..3 position layout.
        List<TermEntry> entries = List.of(
            teNoOffset("foo", 0),
            teNoOffset("bar", 3)
        );
        assertEquals("foo a a bar", LuceneLeafReader.joinWithOffsets(entries, "a"));
    }

    @Test
    void joinWithOffsets_noOffsets_noStopwordConfigured_legacySingleSpaceJoin() {
        // Default behaviour preserved when stopword is not configured: single space between
        // tokens, position gaps are silently lost (the bug the new flag works around).
        List<TermEntry> entries = List.of(
            teNoOffset("i",    0),
            teNoOffset("like", 1),
            teNoOffset("tree", 3)
        );
        assertEquals("i like tree", LuceneLeafReader.joinWithOffsets(entries, null));
        assertEquals("i like tree", LuceneLeafReader.joinWithOffsets(entries, ""));
    }

    @Test
    void joinWithOffsets_offsetsAware_positionGap_insertsStopwordFillerWithinGap() {
        // ES indexed "i like the tree" — offsets are present: i=0..1, like=2..6, tree=11..15.
        // Position gap between "like" and "tree" is 2 (=> one stopword needed). Character gap
        // between like.endOffset=6 and tree.startOffset=11 is 5: " a   " (space + token + 3 pad
        // spaces filling the residual offset gap).
        List<TermEntry> entries = List.of(
            te("i",    0,  0,  1),
            te("like", 1,  2,  6),
            te("tree", 3, 11, 15)
        );
        // Walk: prevEnd=0, "i" gap=0 -> ""; prevEnd=1, "like" gap=1 -> " "; prevEnd=6, "tree"
        // gap=5, posGap=2, filler "a " consumes 1+2=3 chars => 2 trailing pad spaces.
        // Final: "i like a   tree".
        assertEquals("i like a   tree", LuceneLeafReader.joinWithOffsets(entries, "a"));
    }

    @Test
    void joinWithOffsets_offsetsAware_consecutivePositions_unchangedByStopword() {
        // No position gap (every increment is 1) -> stopword has nothing to fill, output is
        // identical to legacy behaviour.
        List<TermEntry> entries = List.of(
            te("date", 0, 0, 4),
            te("wed",  1, 6, 9)
        );
        assertEquals("date  wed", LuceneLeafReader.joinWithOffsets(entries, "a"));
        assertEquals("date  wed", LuceneLeafReader.joinWithOffsets(entries, null));
    }

    @Test
    void joinWithOffsets_offsetsAware_fillerLongerThanGap_doesNotAddNegativeSpaces() {
        // Pathological case: configured stopword is longer than the original offset gap. The
        // filler still goes in (preserving position semantics) but the residual-padding loop
        // must not append negative spaces. Resulting string may be longer than the original
        // surface form — acceptable: the slop/proximity contract trumps surface fidelity.
        List<TermEntry> entries = List.of(
            te("a",    0, 0, 1),
            te("tree", 3, 2, 6)   // posGap=3 -> needs 2 fillers
        );
        // gap=1, posGap=3, filler="stopword" len=8, requested chars = 1 + 2*(8+1) = 19.
        // remaining = 1 - 19 = -18 -> the for(int s=0; s<remaining; s++) loop is a no-op.
        assertEquals("a stopword stopword tree",
            LuceneLeafReader.joinWithOffsets(entries, "stopword"));
    }
}
