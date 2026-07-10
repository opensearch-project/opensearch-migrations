package org.opensearch.migrations.bulkload.workcoordination;

import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit coverage for {@link IWorkCoordinator.WorkItemAndDuration.WorkItem#toString()} and
 * {@link IWorkCoordinator.WorkItemAndDuration.WorkItem#valueFromWorkItemString(String)}.
 *
 * <p>Regression for opensearch-project/opensearch-migrations#2880: the previous serialization
 * simply concatenated {@code indexName + "__" + shard + "__" + docId}, which collided with
 * any index whose name contained the {@code "__"} separator and caused the migration tool
 * to misparse real-world indices.  The current implementation base64url-encodes the index
 * name segment so it can never contain the separator; these tests pin that contract.
 */
class WorkItemStringParsingTest {

    private static IWorkCoordinator.WorkItemAndDuration.WorkItem wi(String name, Integer shard, Long docId) {
        return new IWorkCoordinator.WorkItemAndDuration.WorkItem(name, shard, docId);
    }

    static Stream<Arguments> roundTripIndexNames() {
        return Stream.of(
            // Plain names — the vast majority of real-world indices.
            Arguments.of("logs-2024"),
            Arguments.of("my-index"),
            Arguments.of("simple"),
            // The bug from #2880: names containing the legacy separator must round-trip cleanly.
            Arguments.of("my__index"),
            Arguments.of("__leading"),
            Arguments.of("trailing__"),
            Arguments.of("one__two__three"),
            Arguments.of("a__b__c__d__e"),
            Arguments.of("________"),
            // Names that would otherwise confuse a naive left-to-right parser.
            Arguments.of("contains-0-digits"),
            Arguments.of("has.dots.and-dashes"),
            Arguments.of("with spaces"),
            // Single underscores must survive the base64url alphabet untouched.
            Arguments.of("_one_under_score_"),
            // Non-ASCII / unicode characters — base64url handles raw UTF-8 bytes.
            Arguments.of("índex-ñame"),
            Arguments.of("日本語"),
            // Edge cases around length boundaries where base64 padding used to matter.
            Arguments.of("a"),
            Arguments.of("ab"),
            Arguments.of("abc"),
            Arguments.of("abcd")
        );
    }

    @ParameterizedTest
    @MethodSource("roundTripIndexNames")
    void toString_then_valueFromWorkItemString_preservesAllFields(String indexName) {
        var original = wi(indexName, 7, 42L);
        var serialized = original.toString();

        var parsed = IWorkCoordinator.WorkItemAndDuration.WorkItem.valueFromWorkItemString(serialized);

        Assertions.assertEquals(indexName, parsed.getIndexName(),
            "index name must round-trip verbatim, got serialized=" + serialized);
        Assertions.assertEquals(7, parsed.getShardNumber());
        Assertions.assertEquals(42L, parsed.getStartingDocId());
        Assertions.assertEquals(original, parsed);
    }

    @ParameterizedTest
    @MethodSource("roundTripIndexNames")
    void serializedForm_containsNoDoubleUnderscoreInsideIndexSegment(String indexName) {
        var serialized = wi(indexName, 0, 0L).toString();

        // The serialized form is `<base64>__<shard>__<docId>`.  Splitting on the separator
        // must always yield exactly three components, regardless of the source index name.
        var parts = serialized.split("__");
        Assertions.assertEquals(3, parts.length,
            "serialized work item must split into exactly 3 components on '__', got '"
                + serialized + "' -> " + parts.length);
    }

    @Test
    void shardSetupSentinel_roundTrips() {
        var sentinel = IWorkCoordinator.WorkItemAndDuration.WorkItem
            .valueFromWorkItemString("shard_setup");
        Assertions.assertEquals("shard_setup", sentinel.getIndexName());
        Assertions.assertNull(sentinel.getShardNumber());
        Assertions.assertNull(sentinel.getStartingDocId());
        Assertions.assertEquals("shard_setup", sentinel.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not enough parts",
        "only_one_segment",
        "two__segments",
        // Index-name segment must decode as base64url; a '!' is outside the alphabet.
        "not!base64__0__0"
    })
    void malformedIds_throwIllegalArgumentException(String malformed) {
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> IWorkCoordinator.WorkItemAndDuration.WorkItem.valueFromWorkItemString(malformed));
    }

    @Test
    void constructor_acceptsIndexNameContainingSeparator() {
        // The pre-fix constructor rejected any name containing "__".  The base64 encoding
        // makes that guard unnecessary; this test pins that the guard is gone.
        Assertions.assertDoesNotThrow(() -> wi("anything__goes__here", 0, 0L));
    }
}
