package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SolrDocumentSourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void listCollectionsDelegatesToClient() throws IOException {
        var client = new FakeSolrClient(List.of("col1", "col2"));
        var source = new SolrDocumentSource(client);

        var collections = source.listCollections();
        assertThat(collections.size(), equalTo(2));
        assertThat(collections.get(0), equalTo("col1"));
    }

    @Test
    void listPartitionsReturnsSinglePartition() throws IOException {
        var client = new FakeSolrClient(List.of("col1"));
        var source = new SolrDocumentSource(client);

        var partitions = source.listPartitions("col1");
        assertThat(partitions.size(), equalTo(1));
        assertThat(partitions.get(0).collectionName(), equalTo("col1"));
    }

    @Test
    void readDocumentsStreamsFromCursor() {
        var docs = MAPPER.createArrayNode();
        docs.add(doc("1", "First"));
        docs.add(doc("2", "Second"));

        var client = new FakeSolrClient(List.of("col1"));
        client.setQueryResponse(docs, "DONE", 2);

        var source = new SolrDocumentSource(client, 10);
        var partition = new SolrShardPartition("col1", "shard1");

        var documents = source.readDocuments(partition, 0).collectList().block();
        assertNotNull(documents);
        assertThat(documents.size(), equalTo(2));
        assertThat(documents.get(0).id(), equalTo("1"));
        assertThat(documents.get(1).id(), equalTo("2"));
        assertThat(documents.get(0).sourceLength(), greaterThan(0));
    }

    @Test
    void readDocumentsRespectsStartingOffset() {
        var docs = MAPPER.createArrayNode();
        docs.add(doc("1", "First"));
        docs.add(doc("2", "Second"));
        docs.add(doc("3", "Third"));

        var client = new FakeSolrClient(List.of("col1"));
        client.setQueryResponse(docs, "DONE", 3);

        var source = new SolrDocumentSource(client, 10);
        var partition = new SolrShardPartition("col1", "shard1");

        var documents = source.readDocuments(partition, 2).collectList().block();
        assertNotNull(documents);
        assertThat(documents.size(), equalTo(1));
        assertThat(documents.get(0).id(), equalTo("3"));
    }

    @Test
    void readCollectionMetadataReturnsConfig() throws IOException {
        var client = new FakeSolrClient(List.of("col1"));
        var source = new SolrDocumentSource(client);

        var metadata = source.readCollectionMetadata("col1");
        assertThat(metadata.name(), equalTo("col1"));
        assertNotNull(metadata.sourceConfig());
    }

    private static ObjectNode doc(String id, String title) {
        var node = MAPPER.createObjectNode();
        node.put("id", id);
        node.put("title", title);
        return node;
    }

    /**
     * Fake SolrClient for unit testing without network calls.
     */
    static class FakeSolrClient extends SolrClient {
        private final List<String> collections;
        private ArrayNode queryDocs;
        private String nextCursor = "*";
        private long numFound;
        private boolean queried;

        FakeSolrClient(List<String> collections) {
            super("http://fake:8983");
            this.collections = collections;
        }

        void setQueryResponse(ArrayNode docs, String nextCursor, long numFound) {
            this.queryDocs = docs;
            this.nextCursor = nextCursor;
            this.numFound = numFound;
        }

        @Override
        public List<String> listCollections() {
            return collections;
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode getSchema(String collection) {
            var schema = MAPPER.createObjectNode();
            var schemaInner = MAPPER.createObjectNode();
            schemaInner.set("fields", MAPPER.createArrayNode());
            schema.set("schema", schemaInner);
            return schema;
        }

        @Override
        public SolrQueryResponse query(String collection, String cursorMark, int rows) {
            if (queried) {
                // Return empty on second call to signal end
                return new SolrQueryResponse(MAPPER.createArrayNode(), cursorMark, numFound);
            }
            queried = true;
            return new SolrQueryResponse(
                queryDocs != null ? queryDocs : MAPPER.createArrayNode(),
                nextCursor,
                numFound
            );
        }
    }
}
