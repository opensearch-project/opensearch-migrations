package org.opensearch.migrations;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for the manual JSON parsing helpers used to discover Solr cores/collections.
 *
 * <p>Regression tests for a bug where {@code parseJsonObjectKeys("status")} matched
 * {@code "status":0} inside {@code responseHeader} instead of the actual
 * {@code "status":{"dummy":{...}}} object, causing 0 cores to be discovered.
 */
public class TestSolrJsonParsing {

    /**
     * Exact JSON shape from a real Solr 8.11.4 Core Admin STATUS response.
     * The bug: "status":0 in responseHeader was matched first, then indexOf('{')
     * found initFailures:{} — an empty object — yielding 0 keys.
     */
    private static final String SOLR_CORE_ADMIN_RESPONSE =
        "{\"responseHeader\":{\"status\":0,\"QTime\":5},"
        + "\"initFailures\":{},"
        + "\"status\":{\"dummy\":{\"name\":\"dummy\","
        + "\"instanceDir\":\"/var/solr/data/dummy\","
        + "\"dataDir\":\"/var/solr/data/dummy/data/\","
        + "\"config\":\"solrconfig.xml\","
        + "\"schema\":\"managed-schema\"}}}";

    @Test
    public void testParseJsonObjectKeys_solrCoreAdminResponse_findsDummyCore() {
        var keys = CreateSnapshot.parseJsonObjectKeys(SOLR_CORE_ADMIN_RESPONSE, "status");
        Assertions.assertEquals(List.of("dummy"), keys,
            "Should find 'dummy' core in status object, not be confused by responseHeader.status:0");
    }

    @Test
    public void testParseJsonObjectKeys_multipleCores() {
        var json = "{\"responseHeader\":{\"status\":0,\"QTime\":3},"
            + "\"status\":{\"core1\":{\"name\":\"core1\"},\"core2\":{\"name\":\"core2\"}}}";

        var keys = CreateSnapshot.parseJsonObjectKeys(json, "status");
        Assertions.assertEquals(2, keys.size());
        Assertions.assertTrue(keys.contains("core1"));
        Assertions.assertTrue(keys.contains("core2"));
    }

    @Test
    public void testParseJsonObjectKeys_emptyCoresObject() {
        var json = "{\"responseHeader\":{\"status\":0,\"QTime\":1},\"status\":{}}";
        var keys = CreateSnapshot.parseJsonObjectKeys(json, "status");
        Assertions.assertTrue(keys.isEmpty());
    }

    @Test
    public void testParseJsonObjectKeys_fieldNotPresent() {
        var json = "{\"responseHeader\":{\"QTime\":1}}";
        var keys = CreateSnapshot.parseJsonObjectKeys(json, "status");
        Assertions.assertTrue(keys.isEmpty());
    }

    @Test
    public void testParseJsonObjectKeys_onlyNumericStatus_noCoresObject() {
        // Edge case: only the responseHeader status exists, no top-level status object
        var json = "{\"responseHeader\":{\"status\":0,\"QTime\":1}}";
        var keys = CreateSnapshot.parseJsonObjectKeys(json, "status");
        Assertions.assertTrue(keys.isEmpty(),
            "Should return empty when 'status' only appears as a number, not an object");
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
