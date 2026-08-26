package org.opensearch.migrations.bulkload.pipeline.source;

import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.Partition;
import org.opensearch.migrations.bulkload.pipeline.model.PositionedDocument;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviour every {@link DocumentSource} must exhibit. Extend it from each implementation and
 * supply a source over known data; the suite asserts the contract, not the data.
 *
 * <p>Nothing here asserts enumeration order: everything is addressed by name.
 */
public abstract class DocumentSourceContractTest {

    /**
     * A fresh source over the same underlying data each time. Called repeatedly — two sources must
     * be able to exist at once.
     */
    protected abstract DocumentSource newSource() throws Exception;

    /** A collection the source offers that has at least one partition with at least two documents. */
    protected abstract String collectionUnderTest();

    @Test
    void listCollections_returnsTheSameSetAcrossCallsAndSources() throws Exception {
        HashSet<String> first;
        try (var source = newSource()) {
            first = new HashSet<>(source.listCollections());
            var second = new HashSet<>(source.listCollections());

            assertThat(first, not(empty()));
            assertThat("repeated listCollections must return the same set", second, equalTo(first));
        }
        assertThat("a fresh source must return the same set",
            new HashSet<>(collectionsFrom()), equalTo(first));
    }

    @Test
    void collectionNames_areUnique() throws Exception {
        try (var source = newSource()) {
            var names = source.listCollections();

            assertThat(names, not(empty()));
            assertThat("collection names must be unique",
                new HashSet<>(names), hasSize(names.size()));
        }
    }

    @Test
    void partitionNames_areUniqueWithinACollection() throws Exception {
        try (var source = newSource()) {
            var names = namesOf(source.listPartitions(collectionUnderTest()));

            assertThat(names, not(empty()));
            assertThat("partition names must be unique within a collection",
                new HashSet<>(names), hasSize(names.size()));
        }
    }

    @Test
    void listPartitions_returnsTheSameSetAcrossCallsAndSources() throws Exception {
        try (var source = newSource()) {
            var first = new HashSet<>(namesOf(source.listPartitions(collectionUnderTest())));
            var second = new HashSet<>(namesOf(source.listPartitions(collectionUnderTest())));
            assertThat("repeated listPartitions must return the same set", second, equalTo(first));

            try (var fresh = newSource()) {
                var fromFresh = new HashSet<>(namesOf(fresh.listPartitions(collectionUnderTest())));
                assertThat("a fresh source must return the same set", fromFresh, equalTo(first));
            }
        }
    }

    @Test
    void findPartition_resolvesEveryListedNameAndRejectsOthers() throws Exception {
        try (var source = newSource()) {
            var partitions = source.listPartitions(collectionUnderTest());
            assertThat(partitions, not(empty()));

            for (var partition : partitions) {
                var found = source.findPartition(collectionUnderTest(), partition.name());
                assertTrue(found.isPresent(),
                    "findPartition must resolve the name listPartitions returned: " + partition.name());
                assertThat(found.get().name(), equalTo(partition.name()));
                assertThat(found.get().collectionName(), equalTo(partition.collectionName()));
            }

            assertThat("an unknown partition name must not resolve",
                source.findPartition(collectionUnderTest(), "definitely-not-a-partition").isPresent(),
                is(false));
        }
    }

    @Test
    void resumingFromACursorSkipsExactlyWhatCameBeforeIt() throws Exception {
        try (var source = newSource()) {
            var partition = firstPartitionWithAtLeastTwoDocs(source);
            var all = readAll(source, partition, null);

            // Resume after the first document: the rest of the stream must follow, unchanged.
            var resumeAfter = all.get(0).cursorAfter();
            try (var fresh = newSource()) {
                var resumed = readAll(fresh, resolve(fresh, partition), resumeAfter);

                assertThat("resuming must not re-emit the document its cursor came from",
                    idsOf(resumed), equalTo(idsOf(all.subList(1, all.size()))));
            }
        }
    }

    @Test
    void resumingFromTheLastCursorYieldsNothing() throws Exception {
        try (var source = newSource()) {
            var partition = firstPartitionWithAtLeastTwoDocs(source);
            var all = readAll(source, partition, null);

            var afterEverything = all.get(all.size() - 1).cursorAfter();
            try (var fresh = newSource()) {
                var resumed = readAll(fresh, resolve(fresh, partition), afterEverything);

                assertThat("nothing follows the last document's cursor", resumed, empty());
            }
        }
    }

    @Test
    void readingIsRepeatableFromTheSameCursor() throws Exception {
        try (var source = newSource()) {
            var partition = firstPartitionWithAtLeastTwoDocs(source);

            var first = readAll(source, partition, null);
            try (var fresh = newSource()) {
                var second = readAll(fresh, resolve(fresh, partition), null);

                assertThat("the same cursor must replay the same documents",
                    idsOf(second), equalTo(idsOf(first)));
            }
        }
    }

    private List<String> collectionsFrom() throws Exception {
        try (var source = newSource()) {
            return source.listCollections();
        }
    }

    /** Re-resolves a partition against another source instance, since Partition may hold state. */
    private Partition resolve(DocumentSource source, Partition partition) {
        return source.findPartition(partition.collectionName(), partition.name())
            .orElseThrow(() -> new AssertionError("partition vanished: " + partition.name()));
    }

    private Partition firstPartitionWithAtLeastTwoDocs(DocumentSource source) {
        for (var partition : source.listPartitions(collectionUnderTest())) {
            if (readAll(source, partition, null).size() >= 2) {
                return partition;
            }
        }
        throw new AssertionError("collection " + collectionUnderTest()
            + " has no partition with at least two documents; the resume contract cannot be tested");
    }

    private static List<PositionedDocument> readAll(DocumentSource source, Partition partition, String cursor) {
        var read = source.readDocuments(partition, cursor).collectList().block();
        return read == null ? List.of() : read;
    }

    private static List<String> namesOf(List<Partition> partitions) {
        return partitions.stream().map(Partition::name).collect(Collectors.toList());
    }

    private static List<String> idsOf(List<PositionedDocument> documents) {
        return documents.stream()
            .map(PositionedDocument::document)
            .map(Document::id)
            .collect(Collectors.toList());
    }

    /** Guards the fixture itself: a source with no documents proves nothing about resume. */
    @Test
    void fixtureHasDocumentsToRead() throws Exception {
        try (var source = newSource()) {
            var partition = firstPartitionWithAtLeastTwoDocs(source);
            assertThat(readAll(source, partition, null).size(), greaterThan(1));
        }
    }
}
