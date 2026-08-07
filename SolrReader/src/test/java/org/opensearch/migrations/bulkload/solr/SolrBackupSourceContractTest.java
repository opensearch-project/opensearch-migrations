package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSourceContractTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import shadow.lucene9.org.apache.lucene.analysis.core.KeywordAnalyzer;
import shadow.lucene9.org.apache.lucene.document.Document;
import shadow.lucene9.org.apache.lucene.document.StoredField;
import shadow.lucene9.org.apache.lucene.document.StringField;
import shadow.lucene9.org.apache.lucene.index.IndexWriter;
import shadow.lucene9.org.apache.lucene.index.IndexWriterConfig;
import shadow.lucene9.org.apache.lucene.store.FSDirectory;

/**
 * Runs the source contract against a two-shard Solr backup built from real Lucene 9 indexes.
 */
class SolrBackupSourceContractTest extends DocumentSourceContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COLLECTION = "contract_collection";

    @TempDir
    Path backupDir;

    private boolean built;

    @Override
    protected DocumentSource newSource() throws IOException {
        if (!built) {
            writeShard(backupDir.resolve("shard1"), "s1");
            writeShard(backupDir.resolve("shard2"), "s2");
            built = true;
        }
        return new SolrBackupSource(backupDir, COLLECTION, emptySchema(), 9);
    }

    @Override
    protected String collectionUnderTest() {
        return COLLECTION;
    }

    /** Three docs per shard, so the resume tests have something after the first document. */
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

    private static com.fasterxml.jackson.databind.JsonNode emptySchema() {
        var schema = MAPPER.createObjectNode();
        schema.set("fields", MAPPER.createArrayNode());
        return schema;
    }
}
