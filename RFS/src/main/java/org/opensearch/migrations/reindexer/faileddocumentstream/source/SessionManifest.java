package org.opensearch.migrations.reindexer.faileddocumentstream.source;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The inventory published when a session is sealed: every collection, partition and object key.
 *
 * <p>A source enumerates from this, never from a live listing, which fixes both the partition set
 * and the object order a cursor is recorded against.
 *
 * <p>Construction sorts everything, so two workers building a manifest for the same session produce
 * identical bytes.
 */
public record SessionManifest(
    int schemaVersion,
    String sessionId,
    List<CollectionEntry> collections
) {
    /** A reader rejects any other version rather than guessing. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public SessionManifest {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        collections = collections == null ? List.of()
            : collections.stream()
                .sorted(Comparator.comparing(CollectionEntry::name))
                .toList();
    }

    /** An {@code index=} segment and the partitions under it. */
    public record CollectionEntry(String name, List<PartitionEntry> partitions) {
        public CollectionEntry {
            Objects.requireNonNull(name, "collection name must not be null");
            partitions = partitions == null ? List.of()
                : partitions.stream()
                    .sorted(Comparator.comparing(PartitionEntry::name))
                    .toList();
        }
    }

    /**
     * A {@code worker=} segment and its objects, in read order.
     *
     * <p>Lexicographic, not chronological: {@code seq} is unpadded, so {@code -10} sorts before
     * {@code -2}. Left alone, since re-sorting would invalidate recorded cursors.
     */
    public record PartitionEntry(String name, List<String> objectKeys) {
        public PartitionEntry {
            Objects.requireNonNull(name, "partition name must not be null");
            objectKeys = objectKeys == null ? List.of() : objectKeys.stream().sorted().toList();
        }
    }

    public List<String> collectionNames() {
        return collections.stream().map(CollectionEntry::name).toList();
    }

    public Optional<CollectionEntry> collection(String name) {
        return collections.stream().filter(c -> c.name().equals(name)).findFirst();
    }

    public Optional<PartitionEntry> partition(String collectionName, String partitionName) {
        return collection(collectionName)
            .flatMap(c -> c.partitions().stream().filter(p -> p.name().equals(partitionName)).findFirst());
    }

    /** Every object key the manifest names. Used to check a live listing. */
    public List<String> allObjectKeys() {
        return collections.stream()
            .flatMap(c -> c.partitions().stream())
            .flatMap(p -> p.objectKeys().stream())
            .sorted()
            .toList();
    }
}
