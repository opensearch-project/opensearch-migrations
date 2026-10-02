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
 * <p>Serialization used to concatenate the raw segments, so any index name containing the
 * separator was misparsed. All three segments are now base64url-encoded and the separator is
 * outside that alphabet, so no segment value can collide with it.
 */
class WorkItemStringParsingTest {

    private static IWorkCoordinator.WorkItemAndDuration.WorkItem wi(String name, String partition, String cursor) {
        return new IWorkCoordinator.WorkItemAndDuration.WorkItem(name, partition, cursor);
    }

    static Stream<Arguments> roundTripSegments() {
        return Stream.of(
            // Plain names — the vast majority of real-world indices.
            Arguments.of("logs-2024"),
            Arguments.of("my-index"),
            Arguments.of("simple"),
            // Names containing the legacy separator must round-trip cleanly.
            Arguments.of("my__index"),
            Arguments.of("__leading"),
            Arguments.of("trailing__"),
            Arguments.of("one__two__three"),
            Arguments.of("a__b__c__d__e"),
            Arguments.of("________"),
            // Names containing the current separator must round-trip too.
            Arguments.of("."),
            Arguments.of("a.b.c"),
            Arguments.of(".leading"),
            Arguments.of("trailing."),
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

    /** Every segment is now an arbitrary string, so each is exercised with the same awkward values. */
    @ParameterizedTest
    @MethodSource("roundTripSegments")
    void toString_then_valueFromWorkItemString_preservesAllFields(String segment) {
        var original = wi(segment, segment + "/shard", "cursor:" + segment);
        var serialized = original.toString();

        var parsed = IWorkCoordinator.WorkItemAndDuration.WorkItem.valueFromWorkItemString(serialized);

        Assertions.assertEquals(segment, parsed.getIndexName(),
            "index name must round-trip verbatim, got serialized=" + serialized);
        Assertions.assertEquals(segment + "/shard", parsed.getPartitionName());
        Assertions.assertEquals("cursor:" + segment, parsed.getCursor());
        Assertions.assertEquals(original, parsed);
    }

    @ParameterizedTest
    @MethodSource("roundTripSegments")
    void serializedForm_alwaysSplitsIntoThreeSegments(String segment) {
        var serialized = wi(segment, segment, segment).toString();

        var parts = serialized.split("\\.");
        Assertions.assertEquals(3, parts.length,
            "serialized work item must split into exactly 3 components on '.', got '"
                + serialized + "' -> " + parts.length);
    }

    /** A null cursor means "start at the beginning" and must survive the round trip as null. */
    @Test
    void nullCursor_roundTripsAsNull() {
        var serialized = wi("logs", "shard1", null).toString();

        var parsed = IWorkCoordinator.WorkItemAndDuration.WorkItem.valueFromWorkItemString(serialized);

        Assertions.assertEquals("logs", parsed.getIndexName());
        Assertions.assertEquals("shard1", parsed.getPartitionName());
        Assertions.assertNull(parsed.getCursor());
    }

    @Test
    void shardSetupSentinel_roundTrips() {
        var sentinel = IWorkCoordinator.WorkItemAndDuration.WorkItem
            .valueFromWorkItemString("shard_setup");
        Assertions.assertEquals("shard_setup", sentinel.getIndexName());
        Assertions.assertNull(sentinel.getPartitionName());
        Assertions.assertNull(sentinel.getCursor());
        Assertions.assertEquals("shard_setup", sentinel.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not enough parts",
        "only_one_segment",
        "two.segments",
        "four.of.the.segments",
        // Segments must decode as base64url; a '!' is outside the alphabet.
        "not!base64.aaaa.bbbb"
    })
    void malformedIds_throwIllegalArgumentException(String malformed) {
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> IWorkCoordinator.WorkItemAndDuration.WorkItem.valueFromWorkItemString(malformed));
    }

    @Test
    void constructor_acceptsSegmentsContainingSeparators() {
        Assertions.assertDoesNotThrow(() -> wi("anything__goes__here", "and.dots.too", "cursor.0"));
    }
}
