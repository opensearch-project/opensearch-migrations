package org.opensearch.migrations.transform.shim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.opensearch.migrations.transform.shim.validation.Target;
import org.opensearch.migrations.transform.shim.validation.ValidationRule;

import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ShimMain's static parsing methods to cover
 * branches in parseTargets, parseActiveTargets, parseValidatorSpec, and parseAuthSpec.
 */
class ShimMainParsingTest {

    @TempDir
    Path tempDir;

    // --- parseActiveTargets ---

    @Test
    void parseActiveTargetsReturnsAllWhenActiveIsNull() {
        var params = new ShimMain.Parameters();
        params.active = null;
        Map<String, Target> targets = Map.of(
            "solr", new Target("solr", java.net.URI.create("http://solr:8983"), null, null, null),
            "os", new Target("os", java.net.URI.create("http://os:9200"), null, null, null)
        );
        Set<String> result = ShimMain.parseActiveTargets(params, targets);
        assertEquals(targets.keySet(), result);
    }

    @Test
    void parseActiveTargetsReturnsAllWhenActiveIsEmpty() {
        var params = new ShimMain.Parameters();
        params.active = "";
        Map<String, Target> targets = Map.of(
            "solr", new Target("solr", java.net.URI.create("http://solr:8983"), null, null, null)
        );
        Set<String> result = ShimMain.parseActiveTargets(params, targets);
        assertEquals(targets.keySet(), result);
    }

    @Test
    void parseActiveTargetsFiltersToSpecified() {
        var params = new ShimMain.Parameters();
        params.active = "solr";
        Map<String, Target> targets = new LinkedHashMap<>();
        targets.put("solr", new Target("solr", java.net.URI.create("http://solr:8983"), null, null, null));
        targets.put("os", new Target("os", java.net.URI.create("http://os:9200"), null, null, null));
        Set<String> result = ShimMain.parseActiveTargets(params, targets);
        assertEquals(Set.of("solr"), result);
    }

    @Test
    void parseActiveTargetsThrowsForUnknownTarget() {
        var params = new ShimMain.Parameters();
        params.active = "unknown";
        Map<String, Target> targets = Map.of(
            "solr", new Target("solr", java.net.URI.create("http://solr:8983"), null, null, null)
        );
        assertThrows(ParameterException.class, () -> ShimMain.parseActiveTargets(params, targets));
    }

    // --- parseValidatorSpec ---

    @Test
    void parseFieldEqualityValidator() throws IOException {
        ValidationRule rule = ShimMain.parseValidatorSpec("field-equality:solr,os:ignore=responseHeader.QTime,responseHeader.status");
        assertEquals("field-equality", rule.name());
        assertEquals(List.of("solr", "os"), rule.targetNames());
    }

    @Test
    void parseFieldEqualityValidatorNoIgnore() throws IOException {
        ValidationRule rule = ShimMain.parseValidatorSpec("field-equality:solr,os");
        assertEquals("field-equality", rule.name());
    }

    @Test
    void parseDocCountValidator() throws IOException {
        ValidationRule rule = ShimMain.parseValidatorSpec("doc-count:solr,os:assert=solr<=os");
        assertEquals("doc-count", rule.name());
    }

    @Test
    void parseDocCountValidatorGreaterOrEqual() throws IOException {
        ValidationRule rule = ShimMain.parseValidatorSpec("doc-count:solr,os:assert=solr>=os");
        assertEquals("doc-count", rule.name());
    }

    @Test
    void parseDocCountValidatorEqual() throws IOException {
        ValidationRule rule = ShimMain.parseValidatorSpec("doc-count:solr,os:assert=solr==os");
        assertEquals("doc-count", rule.name());
    }

    @Test
    void parseDocCountValidatorInvalidAssertion() {
        assertThrows(ParameterException.class,
            () -> ShimMain.parseValidatorSpec("doc-count:solr,os:assert=solr!=os"));
    }

    @Test
    void parseDocCountValidatorMissingAssert() {
        assertThrows(ParameterException.class,
            () -> ShimMain.parseValidatorSpec("doc-count:solr,os:nope"));
    }

    @Test
    void parseDocIdsValidator() throws IOException {
        ValidationRule rule = ShimMain.parseValidatorSpec("doc-ids:solr,os");
        assertEquals("doc-ids", rule.name());
    }

    @Test
    void parseDocIdsValidatorOrdered() throws IOException {
        ValidationRule rule = ShimMain.parseValidatorSpec("doc-ids:solr,os:ordered");
        assertEquals("doc-ids", rule.name());
    }

    @Test
    void parseJsValidator() throws IOException {
        Path jsFile = tempDir.resolve("validator.js");
        Files.writeString(jsFile, "function validate(a, b) { return { passed: true }; }");
        ValidationRule rule = ShimMain.parseValidatorSpec("js:solr,os:script=" + jsFile);
        assertEquals("js", rule.name());
    }

    @Test
    void parseJsValidatorMissingScript() {
        assertThrows(ParameterException.class,
            () -> ShimMain.parseValidatorSpec("js:solr,os:noscript"));
    }

    @Test
    void parseUnknownValidatorType() {
        assertThrows(ParameterException.class,
            () -> ShimMain.parseValidatorSpec("unknown:solr,os"));
    }

    @Test
    void parseValidatorSpecTooFewTargets() {
        assertThrows(ParameterException.class,
            () -> ShimMain.parseValidatorSpec("field-equality:solr"));
    }

    @Test
    void parseValidatorSpecTooFewParts() {
        assertThrows(ParameterException.class,
            () -> ShimMain.parseValidatorSpec("field-equality"));
    }

    // --- parseTargets ---

    @Test
    void parseTargetsBasic() throws IOException {
        var params = new ShimMain.Parameters();
        params.targets = List.of("solr=http://solr:8983", "os=http://os:9200");
        Map<String, Target> targets = ShimMain.parseTargets(params, new LinkedHashMap<>());
        assertEquals(2, targets.size());
        assertTrue(targets.containsKey("solr"));
        assertTrue(targets.containsKey("os"));
    }

    @Test
    void parseTargetsInvalidFormat() {
        var params = new ShimMain.Parameters();
        params.targets = List.of("invalid-no-equals");
        assertThrows(ParameterException.class, () -> ShimMain.parseTargets(params, new LinkedHashMap<>()));
    }

    @Test
    void parseTargetsWithTransform() throws IOException {
        Path reqJs = tempDir.resolve("req.js");
        Files.writeString(reqJs, "function transformRequest(r) { return r; }");
        Path respJs = tempDir.resolve("resp.js");
        Files.writeString(respJs, "function transformResponse(r) { return r; }");

        var params = new ShimMain.Parameters();
        params.targets = List.of("os=http://os:9200");
        params.targetTransforms = List.of("os=request:" + reqJs + ",response:" + respJs);
        Map<String, Target> targets = ShimMain.parseTargets(params, new LinkedHashMap<>());
        assertNotNull(targets.get("os").requestTransform());
        assertNotNull(targets.get("os").responseTransform());
    }

    @Test
    void parseTargetsTransformInvalidPart() throws IOException {
        var params = new ShimMain.Parameters();
        params.targets = List.of("os=http://os:9200");
        params.targetTransforms = List.of("os=badpart:file.js");
        assertThrows(ParameterException.class, () -> ShimMain.parseTargets(params, new LinkedHashMap<>()));
    }

    @Test
    void parseTargetsTransformUnknownTarget() {
        var params = new ShimMain.Parameters();
        params.targets = List.of("os=http://os:9200");
        params.targetTransforms = List.of("unknown=request:file.js");
        assertThrows(ParameterException.class, () -> ShimMain.parseTargets(params, new LinkedHashMap<>()));
    }

    @Test
    void parseTargetsAuthUnknownTarget() {
        var params = new ShimMain.Parameters();
        params.targets = List.of("os=http://os:9200");
        params.targetAuths = List.of("unknown=none");
        assertThrows(ParameterException.class, () -> ShimMain.parseTargets(params, new LinkedHashMap<>()));
    }

    @Test
    void parseTargetsWithBasicAuth() throws IOException {
        var params = new ShimMain.Parameters();
        params.targets = List.of("os=http://os:9200");
        params.targetAuths = List.of("os=basic:admin:password");
        Map<String, Target> targets = ShimMain.parseTargets(params, new LinkedHashMap<>());
        assertNotNull(targets.get("os").authHandlerSupplier());
    }

    @Test
    void parseTargetsWithHeaderAuth() throws IOException {
        var params = new ShimMain.Parameters();
        params.targets = List.of("os=http://os:9200");
        params.targetAuths = List.of("os=header:Bearer mytoken123");
        Map<String, Target> targets = ShimMain.parseTargets(params, new LinkedHashMap<>());
        assertNotNull(targets.get("os").authHandlerSupplier());
    }

    @Test
    void parseTargetsWithNoneAuth() throws IOException {
        var params = new ShimMain.Parameters();
        params.targets = List.of("os=http://os:9200");
        params.targetAuths = List.of("os=none");
        Map<String, Target> targets = ShimMain.parseTargets(params, new LinkedHashMap<>());
        assertNull(targets.get("os").authHandlerSupplier());
    }

    @Test
    void parseTargetsAuthInvalidFormat() {
        var params = new ShimMain.Parameters();
        params.targets = List.of("os=http://os:9200");
        params.targetAuths = List.of("invalid-no-equals");
        assertThrows(ParameterException.class, () -> ShimMain.parseTargets(params, new LinkedHashMap<>()));
    }

    @Test
    void parseTargetsAuthUnknownType() {
        var params = new ShimMain.Parameters();
        params.targets = List.of("os=http://os:9200");
        params.targetAuths = List.of("os=oauth:token");
        assertThrows(ParameterException.class, () -> ShimMain.parseTargets(params, new LinkedHashMap<>()));
    }

    @Test
    void parseTargetsBasicAuthInvalidFormat() {
        var params = new ShimMain.Parameters();
        params.targets = List.of("os=http://os:9200");
        params.targetAuths = List.of("os=basic:onlyuser");
        assertThrows(ParameterException.class, () -> ShimMain.parseTargets(params, new LinkedHashMap<>()));
    }

    // --- initReporting with auth ---

    @Test
    void initReportingWithAuthConfig() throws IOException {
        Path config = tempDir.resolve("reporting.json");
        Files.writeString(config, """
            {
              "enabled": true,
              "include_request_body": true,
              "sink": {
                "opensearch": {
                  "uri": "http://localhost:1",
                  "index_prefix": "test",
                  "bulk_size": 10,
                  "flush_interval_ms": 60000,
                  "auth": {
                    "username": "admin",
                    "password": "secret"
                  }
                }
              }
            }
            """);

        Object[] result = ShimMain.initReporting(config.toString());
        assertNotNull(result[0]);
        assertNotNull(result[1]);
        if (result[1] instanceof AutoCloseable closeable) {
            try { closeable.close(); } catch (Exception ignored) { }
        }
    }
}
