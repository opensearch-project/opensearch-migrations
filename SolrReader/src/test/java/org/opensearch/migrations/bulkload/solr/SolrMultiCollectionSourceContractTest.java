package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSourceContractTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import shadow.lucene9.org.apache.lucene.analysis.core.KeywordAnalyzer;
import shadow.lucene9.org.apache.lucene.document.Document;
import shadow.lucene9.org.apache.lucene.document.StoredField;
import shadow.lucene9.org.apache.lucene.document.StringField;
import shadow.lucene9.org.apache.lucene.index.IndexWriter;
import shadow.lucene9.org.apache.lucene.index.IndexWriterConfig;
import shadow.lucene9.org.apache.lucene.store.FSDirectory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;

/**
 * Runs the source contract against the multi-collection wrapper, which is what the Solr provider
 * actually returns. Covers the lazy per-collection and per-shard preparation the inner
 * {@link SolrBackupSource} does not have.
 */
class SolrMultiCollectionSourceContractTest extends DocumentSourceContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COLLECTION = "movies";
    private static final String OTHER_COLLECTION = "books";

    @TempDir
    Path backupDir;

    private boolean built;

    @Override
    protected DocumentSource newSource() throws IOException {
        buildOnce();
        return new SolrMultiCollectionSource(backupDir, schemas(), null, null, 9);
    }

    @Override
    protected String collectionUnderTest() {
        return COLLECTION;
    }

    /** The wrapper must offer every collection it was given, not just the one under test. */
    @Test
    void listsEveryConfiguredCollection() throws Exception {
        try (var source = newSource()) {
            assertThat(source.listCollections(), containsInAnyOrder(COLLECTION, OTHER_COLLECTION));
        }
    }

    /** Preparation is per collection and per shard, and must not repeat on a second read. */
    @Test
    void preparesEachCollectionAndShardExactlyOnce() throws Exception {
        buildOnce();
        var collectionPreps = new AtomicInteger();
        var shardPreps = new AtomicInteger();

        try (var source = new SolrMultiCollectionSource(backupDir, schemas(),
                c -> collectionPreps.incrementAndGet(), p -> shardPreps.incrementAndGet(), 9)) {
            var partitions = source.listPartitions(COLLECTION);
            for (var partition : partitions) {
                source.readDocuments(partition, null).collectList().block();
                source.readDocuments(partition, null).collectList().block();
            }

            assertThat(collectionPreps.get(), equalTo(1));
            assertThat(shardPreps.get(), equalTo(partitions.size()));
        }
    }

    private void buildOnce() throws IOException {
        if (built) {
            return;
        }
        writeShard(backupDir.resolve(COLLECTION).resolve("shard1"), "m1");
        writeShard(backupDir.resolve(COLLECTION).resolve("shard2"), "m2");
        writeShard(backupDir.resolve(OTHER_COLLECTION).resolve("shard1"), "b1");
        built = true;
    }

    private static Map<String, JsonNode> schemas() {
        var schemas = new LinkedHashMap<String, JsonNode>();
        schemas.put(COLLECTION, null);
        schemas.put(OTHER_COLLECTION, null);
        return schemas;
    }

    private static void writeShard(Path shardDir, String idPrefix) throws IOException {
        Files.createDirectories(shardDir);
        try (var directory = FSDirectory.open(shardDir);
             var writer = new IndexWriter(directory, new IndexWriterConfig(new KeywordAnalyzer()))) {
            for (int i = 0; i < 3; i++) {
                var doc = new Document();
                doc.add(new StringField("id", idPrefix + "-" + i, StringField.Store.YES));
                doc.add(new StoredField("title", "document " + i));
                writer.addDocument(doc);
            }
            writer.commit();
        }
    }
}
