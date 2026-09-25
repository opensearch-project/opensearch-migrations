package org.opensearch.migrations.bulkload.pipeline;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import org.opensearch.migrations.bulkload.pipeline.adapter.EsShardPartition;
import org.opensearch.migrations.bulkload.pipeline.model.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.Partition;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * A shard has two document counts and the backfill must report both: the roots it migrates, which
 * matches {@code <index>/_count}, and the live Lucene total including nested children, which matches
 * {@code _cat/indices docs.count}.
 *
 * <p>Pins the contract that lets a source supply the second number. Sources that cannot produce it
 * cheaply return empty rather than paying for a scan, and the reporter then sees 0.
 */
class LiveLuceneDocCountContractTest {

    private static final EsShardPartition PARTITION =
        new EsShardPartition("snap", "idx", 0);

    /** A source that knows its Lucene total, as the Lucene-backed snapshot source does. */
    private static DocumentSource sourceReporting(long luceneTotal, int rootsEmitted) {
        return new DocumentSource() {
            @Override
            public List<String> listCollections() {
                return List.of("idx");
            }

            @Override
            public List<Partition> listPartitions(String collectionName) {
                return List.of(PARTITION);
            }

            @Override
            public CollectionMetadata readCollectionMetadata(String collectionName) {
                return null;
            }

            @Override
            public Flux<Document> readDocuments(Partition partition, long startingDocOffset) {
                return Flux.range(0, rootsEmitted)
                    .map(i -> new Document(
                        "root" + i,
                        "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        Document.Operation.UPSERT,
                        Map.of(),
                        Map.of(Document.SOURCE_META_LUCENE_DOC_NUMBER, i * 5)));
            }

            @Override
            public OptionalLong countLiveDocuments(Partition partition) {
                return OptionalLong.of(luceneTotal);
            }
        };
    }

    @Test
    void luceneTotalIsReportedSeparatelyFromTheRootCount() {
        // 5 roots, each followed by 4 nested children: 25 live Lucene documents, 5 migrated.
        var source = sourceReporting(25L, 5);

        Assertions.assertEquals(25L, source.countLiveDocuments(PARTITION).orElse(-1),
            "the Lucene total must count nested children, matching _cat/indices docs.count");

        long emitted = source.readDocuments(PARTITION, 0).count().block();
        Assertions.assertEquals(5L, emitted,
            "only root documents are emitted, matching _count");
        Assertions.assertTrue(source.countLiveDocuments(PARTITION).orElse(0) > emitted,
            "the two counts must differ on a nested shard; equality means the Lucene total is "
                + "excluding nested children");
    }

    @Test
    void sourcesThatCannotCountCheaplyReportEmpty() {
        var source = new DocumentSource() {
            @Override
            public List<String> listCollections() {
                return List.of();
            }

            @Override
            public List<Partition> listPartitions(String collectionName) {
                return List.of();
            }

            @Override
            public CollectionMetadata readCollectionMetadata(String collectionName) {
                return null;
            }

            @Override
            public Flux<Document> readDocuments(Partition partition, long startingDocOffset) {
                return Flux.empty();
            }
        };

        Assertions.assertTrue(source.countLiveDocuments(PARTITION).isEmpty(),
            "the default must be empty so a source never pays for a full scan to answer this");
        Assertions.assertEquals(0L, source.countLiveDocuments(PARTITION).orElse(0L),
            "callers treat empty as 0, meaning 'not reported'");
    }

    @Test
    void flatShardReportsEqualCounts() {
        // No nested children: the two counts coincide, which is why a single number hid the
        // distinction for so long.
        var source = sourceReporting(5L, 5);
        long emitted = source.readDocuments(PARTITION, 0).count().block();
        Assertions.assertEquals(emitted, source.countLiveDocuments(PARTITION).orElse(-1),
            "on a flat shard the Lucene total equals the migrated count");
    }
}
