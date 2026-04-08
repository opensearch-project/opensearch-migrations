package org.opensearch.migrations;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for the manual JSON parsing helpers used to discover Solr cores/collections.
 */
public class TestSolrJsonParsing {

    @Test
    public void testParseJsonObjectKeys_solrCoreAdminResponse() {
        // Real Solr Core Admin response has "status":0 in responseHeader AND "status":{cores} at top level
        var json = "{\"responseHeader\":{\"status\":0,\"QTime\":5},"
            + "\"initFailures\":{},"
            + "\"status\":{\"dummy\":{\"name\":\"dummy\",\"instanceDir\":\"/var/solr/data/dummy\"}}}";

        var keys = CreateSnapshot.parseJsonObjectKeys(json, "status");
        Assertions.assertEquals(List.of("dummy"), keys);
    }

    @Test
    public void testParseJsonObjectKeys_multipleCores() {
        var json = "{\"responseHeader\":{\"status\":0,\"QTime\":3},"
            + "\"status\":{\"core1\":{\"name\":\"core1\"},\"core2\":{\"name\":\"core2\"}}}";

        var keys = CreateSnapshot.parseJsonObjectKeys(json, "status");
        Assertions.assertTrue(keys.contains("core1"));
        Assertions.assertTrue(keys.contains("core2"));
        Assertions.assertEquals(2, keys.size());
    }

    @Test
    public void testParseJsonObjectKeys_emptyCores() {
        var json = "{\"responseHeader\":{\"status\":0,\"QTime\":1},\"status\":{}}";

        var keys = CreateSnapshot.parseJsonObjectKeys(json, "status");
        Assertions.assertTrue(keys.isEmpty());
    }

    @Test
    public void testParseJsonObjectKeys_noStatusField() {
        var json = "{\"responseHeader\":{\"QTime\":1}}";

        var keys = CreateSnapshot.parseJsonObjectKeys(json, "status");
        Assertions.assertTrue(keys.isEmpty());
    }

    @Test
    public void testParseJsonStringArray_solrCollectionsList() {
        var json = "{\"responseHeader\":{\"status\":0},\"collections\":[\"coll1\",\"coll2\"]}";

        var collections = CreateSnapshot.parseJsonStringArray(json, "collections");
        Assertions.assertEquals(List.of("coll1", "coll2"), collections);
    }

    @Test
    public void testParseJsonStringArray_emptyCollections() {
        var json = "{\"responseHeader\":{\"status\":0},\"collections\":[]}";

        var collections = CreateSnapshot.parseJsonStringArray(json, "collections");
        Assertions.assertTrue(collections.isEmpty());
    }
}
