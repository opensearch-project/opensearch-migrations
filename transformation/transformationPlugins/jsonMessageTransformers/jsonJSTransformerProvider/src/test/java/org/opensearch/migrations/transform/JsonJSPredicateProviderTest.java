package org.opensearch.migrations.transform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JsonJSPredicateProviderTest {

    private static final String ALLOW_ALL_SCRIPT =
        "(function(bindings) { return function(msg) { return true; }; })";

    private static final String DENY_ALL_SCRIPT =
        "(function(bindings) { return function(msg) { return false; }; })";

    private static final String URI_FILTER_SCRIPT =
        "(function(bindings) {\n" +
        "  return function(msg) {\n" +
        "    var uri = msg.get('URI');\n" +
        "    return uri.indexOf('/select') >= 0 || uri.indexOf('/update') >= 0;\n" +
        "  };\n" +
        "})";

    private static final String SOLR_FILTER_SCRIPT =
        "(function(bindings) {\n" +
        "  return function(msg) {\n" +
        "    var uri = msg.get('URI');\n" +
        "    if (!uri) return true;\n" +
        "    return uri.indexOf('/select') >= 0 || uri.indexOf('/update') >= 0 || uri.indexOf('/get') >= 0;\n" +
        "  };\n" +
        "})";

    private static final String REGEX_FILTER_SCRIPT =
        "(function(bindings) {\n" +
        "  return function(msg) {\n" +
        "    var uri = msg.get('URI');\n" +
        "    return !/\\/(admin|schema|config|stream|sql)/.test(uri);\n" +
        "  };\n" +
        "})";

    private static final String METHOD_AND_URI_FILTER_SCRIPT =
        "(function(bindings) {\n" +
        "  return function(msg) {\n" +
        "    var uri = msg.get('URI');\n" +
        "    var method = msg.get('method');\n" +
        "    if (uri.indexOf('/update') >= 0 && method !== 'POST') return false;\n" +
        "    if (method === 'DELETE') return false;\n" +
        "    return true;\n" +
        "  };\n" +
        "})";

    private final JsonJSPredicateProvider provider = new JsonJSPredicateProvider();

    private IJsonPredicate createPredicate(String script) {
        return provider.createPredicate(Map.of("initializationScript", script));
    }

    private Map<String, Object> makeRequest(String method, String uri) {
        var map = new LinkedHashMap<String, Object>();
        map.put("method", method);
        map.put("URI", uri);
        map.put("protocol", "HTTP/1.1");
        map.put("headers", new LinkedHashMap<>(Map.of("Host", "localhost")));
        return map;
    }

    // --- Basic predicate tests ---

    @Test
    void allowAll_returnsTrue() {
        var predicate = createPredicate(ALLOW_ALL_SCRIPT);
        assertTrue(predicate.test(makeRequest("GET", "/anything")));
    }

    @Test
    void denyAll_returnsFalse() {
        var predicate = createPredicate(DENY_ALL_SCRIPT);
        assertFalse(predicate.test(makeRequest("GET", "/anything")));
    }

    // --- URI filter tests ---

    @Test
    void uriFilter_allowsSelect() {
        var predicate = createPredicate(URI_FILTER_SCRIPT);
        assertTrue(predicate.test(makeRequest("GET", "/solr/products/select?q=*:*")));
    }

    @Test
    void uriFilter_allowsUpdate() {
        var predicate = createPredicate(URI_FILTER_SCRIPT);
        assertTrue(predicate.test(makeRequest("POST", "/solr/products/update?commit=true")));
    }

    @Test
    void uriFilter_blocksAdmin() {
        var predicate = createPredicate(URI_FILTER_SCRIPT);
        assertFalse(predicate.test(makeRequest("GET", "/solr/products/admin/ping")));
    }

    @Test
    void uriFilter_blocksSchema() {
        var predicate = createPredicate(URI_FILTER_SCRIPT);
        assertFalse(predicate.test(makeRequest("GET", "/solr/products/schema/fields")));
    }

    // --- Solr endpoint filter tests ---

    @Test
    void solrFilter_allowsGet() {
        var predicate = createPredicate(SOLR_FILTER_SCRIPT);
        assertTrue(predicate.test(makeRequest("GET", "/solr/products/get?id=1")));
    }

    @Test
    void solrFilter_blocksConfig() {
        var predicate = createPredicate(SOLR_FILTER_SCRIPT);
        assertFalse(predicate.test(makeRequest("GET", "/solr/products/config")));
    }

    // --- Regex filter tests ---

    @Test
    void regexFilter_allowsSelect() {
        var predicate = createPredicate(REGEX_FILTER_SCRIPT);
        assertTrue(predicate.test(makeRequest("GET", "/solr/col/select?q=hello")));
    }

    @Test
    void regexFilter_blocksAdmin() {
        var predicate = createPredicate(REGEX_FILTER_SCRIPT);
        assertFalse(predicate.test(makeRequest("GET", "/solr/col/admin/ping")));
    }

    @Test
    void regexFilter_blocksStream() {
        var predicate = createPredicate(REGEX_FILTER_SCRIPT);
        assertFalse(predicate.test(makeRequest("GET", "/solr/col/stream")));
    }

    @Test
    void regexFilter_blocksSql() {
        var predicate = createPredicate(REGEX_FILTER_SCRIPT);
        assertFalse(predicate.test(makeRequest("POST", "/solr/col/sql")));
    }

    // --- Method + URI combo filter tests ---

    @Test
    void methodUriFilter_allowsPostToUpdate() {
        var predicate = createPredicate(METHOD_AND_URI_FILTER_SCRIPT);
        assertTrue(predicate.test(makeRequest("POST", "/solr/products/update?commit=true")));
    }

    @Test
    void methodUriFilter_blocksGetToUpdate() {
        var predicate = createPredicate(METHOD_AND_URI_FILTER_SCRIPT);
        assertFalse(predicate.test(makeRequest("GET", "/solr/products/update?commit=true")));
    }

    @Test
    void methodUriFilter_blocksDelete() {
        var predicate = createPredicate(METHOD_AND_URI_FILTER_SCRIPT);
        assertFalse(predicate.test(makeRequest("DELETE", "/solr/products/update")));
    }

    @Test
    void methodUriFilter_allowsGetToSelect() {
        var predicate = createPredicate(METHOD_AND_URI_FILTER_SCRIPT);
        assertTrue(predicate.test(makeRequest("GET", "/solr/products/select?q=*:*")));
    }

    // --- File-based filter tests ---

    @Test
    void scriptFile_loadsAndFilters(@TempDir Path tempDir) throws Exception {
        var filterFile = tempDir.resolve("filter.js");
        Files.writeString(filterFile, SOLR_FILTER_SCRIPT);

        var predicate = provider.createPredicate(Map.of("initializationScriptFile", filterFile.toString()));

        assertTrue(predicate.test(makeRequest("GET", "/solr/test/select?q=hello")));
        assertFalse(predicate.test(makeRequest("GET", "/solr/test/admin/ping")));
    }

    // --- Error handling tests ---

    @Test
    void missingConfig_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> provider.createPredicate(null));
    }

    @Test
    void emptyConfig_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> provider.createPredicate(Map.of()));
    }

    @Test
    void invalidScript_throwsException() {
        var config = Map.of("initializationScript", "not valid javascript {{{{");
        assertThrows(Exception.class, () -> provider.createPredicate(config));
    }

    @Test
    void nonexistentFile_throwsIllegalArgument() {
        var config = Map.of("initializationScriptFile", "/nonexistent/path/filter.js");
        assertThrows(IllegalArgumentException.class, () -> provider.createPredicate(config));
    }

    // --- Sequential multi-request test (statelessness) ---

    @Test
    void predicate_handlesMultipleRequestsSequentially() {
        var predicate = createPredicate(SOLR_FILTER_SCRIPT);

        assertTrue(predicate.test(makeRequest("GET", "/solr/a/select?q=1")));
        assertFalse(predicate.test(makeRequest("GET", "/solr/a/admin/ping")));
        assertTrue(predicate.test(makeRequest("POST", "/solr/a/update")));
        assertFalse(predicate.test(makeRequest("GET", "/solr/a/schema")));
        assertTrue(predicate.test(makeRequest("GET", "/solr/a/get?id=1")));
        assertFalse(predicate.test(makeRequest("GET", "/solr/a/config")));
    }
}
