package org.opensearch.migrations.transform;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Slf4j
public class JavascriptTransformerTest {

    private static final String INIT_SCRIPT = "((context) => (document) => ({docSize: Object.keys(document).length+2 }))";
    private static final String INIT_SCRIPT_2 = "\n" +
        "function transformDoc(context, document) { " +
        "    return { docSize: Object.keys(document).length + 2 }; " +
        "} " +
        "function main(context) { " +
        "    return function(document) { " +
        "        return transformDoc(context, document); " +
        "    }; " +
        "}" +
        "main";

    @Test
    @SuppressWarnings("unchecked")
    public void testInlinedScriptPerformance() throws Exception {
        log.atInfo().setMessage("Starting initScript1 run").log();
        testScriptPerformance(INIT_SCRIPT);

        log.atInfo().setMessage("Starting initScript2 run").log();
        testScriptPerformance(INIT_SCRIPT_2);
    }

    private void testScriptPerformance(String script) throws Exception {
        try (var testTransformer = new JavascriptTransformer(script, Map.of("document", Map.of()))) {
            var testDoc = Map.of("hi", (Object) "world");
            for (int j = 0; j < 20; ++j) {
                var start = System.nanoTime();
                var count = 0;
                for (int i = 0; i < 1000; ++i) {
                    count += ((Map<String, Object>) testTransformer.transformJson(testDoc)).size();
                }
                log.atInfo().setMessage("Run {}: {}")
                    .addArgument(j)
                    .addArgument(Duration.ofNanos(System.nanoTime() - start)).log();
            }
        }
    }

    private static class CustomMapAlwaysContainsKey extends HashMap<String, Object> {
        @Override
        public boolean containsKey(Object key) {
            return true;
        }
    }

    @Test
    public void testCustomMap() throws Exception {
        var scriptCallsHas = "((ignored) => (map) => (map.has('missing')))";
        try (var testTransformer = new JavascriptTransformer(scriptCallsHas, Map.of())) {
            var result = testTransformer.transformJson(new CustomMapAlwaysContainsKey());
            Assertions.assertEquals(true, result);
            Assertions.assertEquals(Boolean.class, result.getClass());
        }

        var scriptReturnsNewArray = "((ignored) => (map) => (['output']))";
        try (var testTransformer = new JavascriptTransformer(scriptReturnsNewArray, Map.of())) {
            var result = testTransformer.transformJson(new CustomMapAlwaysContainsKey());
            Assertions.assertEquals(List.of("output"), result);
            Assertions.assertEquals("com.oracle.truffle.polyglot.PolyglotList", result.getClass().getName());
        }

        var scriptReturnsInputMap = "((ignored) => (map) => (map))";
        try (var testTransformer = new JavascriptTransformer(scriptReturnsInputMap, Map.of())) {
            var input = new CustomMapAlwaysContainsKey();
            var result = testTransformer.transformJson(input);
            Assertions.assertEquals(input, result);
            Assertions.assertInstanceOf(CustomMapAlwaysContainsKey.class, result);
        }
    }

    @Test
    public void testMapOperations() throws Exception {
        var scriptCallsHas = "((map) => (map.has('foo')))";
        try (var testTransformer = new JavascriptTransformer(scriptCallsHas, null)) {
            var result = testTransformer.transformJson(Map.of("foo", "bar"));
            Assertions.assertEquals(true, result);
        }

        var scriptCallsGet = "((map) => (map.get('foo')))";
        try (var testTransformer = new JavascriptTransformer(scriptCallsGet, null)) {
            var result = testTransformer.transformJson(Map.of("foo", "bar"));
            Assertions.assertEquals("bar", result);
        }

        var scriptCallsDotAccess = "((map) => (map.foo))";
        try (var testTransformer = new JavascriptTransformer(scriptCallsDotAccess, null)) {
            var result = testTransformer.transformJson(Map.of("foo", "bar"));
            Assertions.assertEquals("bar", result);
        }

        var scriptCallsDotModify = "(map) => { map.foo = 'modified'; return map }";
        try (var testTransformer = new JavascriptTransformer(scriptCallsDotModify, null)) {
            var map = new HashMap<String, Object>();
            map.put("foo", "bar");
            var result = testTransformer.transformJson(map);
            Assertions.assertEquals(Map.of("foo", "modified"), result);
        }

        var scriptCallsBracketGet = "((map) => (map['foo']))";
        try (var testTransformer = new JavascriptTransformer(scriptCallsBracketGet, null)) {
            var result = testTransformer.transformJson(Map.of("foo", "bar"));
            Assertions.assertEquals("bar", result);
        }

        var scriptCallsBracketModify = "((map) => { map['foo'] = 'modified'; return map })";
        try (var testTransformer = new JavascriptTransformer(scriptCallsBracketModify, null)) {
            var map = new HashMap<String, Object>();
            map.put("foo", "bar");
            var result = testTransformer.transformJson(map);
            Assertions.assertEquals(Map.of("foo", "modified"), result);
        }

        var scriptCallsInFails = "(map) => ('foo' in map)";
        try (var testTransformer = new JavascriptTransformer(scriptCallsInFails, null)) {
            var result = testTransformer.transformJson(Map.of("foo", "bar"));
            Assertions.assertThrows(AssertionError.class, () ->
                            Assertions.assertEquals(true, result),
                    "The 'key' in map operation is not expected to operate on the keys of a map");
        }

        var scriptCallsGetWithoutKey = "(map) => (map.baz)";
        try (var testTransformer = new JavascriptTransformer(scriptCallsGetWithoutKey, null)) {
            var result = testTransformer.transformJson(Map.of());
            Assertions.assertNull(result);
        }

        var scriptModifiesWithImmutableMap = "(map) => (map.set('foo', 'baz'))";
        try (var testTransformer = new JavascriptTransformer(scriptModifiesWithImmutableMap, null)) {
            var immutableMap = Map.of("foo", "baz");
            PolyglotException exception = Assertions.assertThrows(PolyglotException.class, () -> {
                testTransformer.transformJson(immutableMap);
            });
            // Verify that the cause is UnsupportedOperationException
            Assertions.assertInstanceOf(UnsupportedOperationException.class, exception.asHostException(),
                    "Expected cause to be UnsupportedOperationException, but got: " + exception.getCause());
        }
    }

    public static class TestJson {
        @HostAccess.Export
        public String foo = "bar";
    }
    // JSON objects are usually represented as maps, if they are native java objects, verify GraalJS behavior
    @Test
    public void testObjectOperations() throws Exception {
        var scriptCallsHas = "((obj) => (obj.has('foo')))";
        try (var testTransformer = new JavascriptTransformer(scriptCallsHas, null)) {
            Assertions.assertThrows(
                    org.graalvm.polyglot.PolyglotException.class,
                    () -> testTransformer.transformJson(new TestJson()),
                    "Expected TypeError due to unknown identifier 'has'"
            );
        }

        var scriptCallsGet = "((obj) => (obj.get('foo')))";
        try (var testTransformer = new JavascriptTransformer(scriptCallsGet, null)) {
            Assertions.assertThrows(
                    org.graalvm.polyglot.PolyglotException.class,
                    () -> testTransformer.transformJson(new TestJson()),
                    "Expected TypeError due to unknown identifier 'get'"
            );
        }

        var scriptCallsDotAccess = "((obj) => (obj.foo))";
        try (var testTransformer = new JavascriptTransformer(scriptCallsDotAccess, null)) {
            var result = testTransformer.transformJson(new TestJson());
            Assertions.assertEquals("bar", result);
        }

        var scriptCallsDotModify = "(obj) => { obj.foo = 'modified'; return obj }";
        try (var testTransformer = new JavascriptTransformer(scriptCallsDotModify, null)) {
            var obj = new TestJson();
            testTransformer.transformJson(obj);
            Assertions.assertEquals("modified", obj.foo);
        }

        var scriptCallsBracketGet = "((obj) => (obj['foo']))";
        try (var testTransformer = new JavascriptTransformer(scriptCallsBracketGet, null)) {
            var result = testTransformer.transformJson(new TestJson());
            Assertions.assertEquals("bar", result);
        }

        var scriptCallsBracketModify = "((obj) => { obj['foo'] = 'modified'; return obj })";
        try (var testTransformer = new JavascriptTransformer(scriptCallsBracketModify, null)) {
            var obj = new TestJson();
            testTransformer.transformJson(obj);
            Assertions.assertEquals("modified", obj.foo);
        }

        var scriptCallsInFails = "(obj) => ('foo' in obj)";
        try (var testTransformer = new JavascriptTransformer(scriptCallsInFails, null)) {
            var result = testTransformer.transformJson(new TestJson());
            Assertions.assertEquals(true, result);
        }

        var scriptCallsGetWithoutKey = "(obj) => (obj.baz)";
        try (var testTransformer = new JavascriptTransformer(scriptCallsGetWithoutKey, null)) {
            var result = testTransformer.transformJson(new TestJson());
            Assertions.assertNull(result);
        }
    }


        @Test
    public void testPromiseHandling() throws Exception {
        // Script that returns a resolved Promise
        var successScript =
                "((ignored) => (map) => {" +
                        "return new Promise((resolve, reject) => {" +
                        "resolve(['output']);" +
                        "});" +
                        "});";

        // Test success case
        try (var testTransformer = new JavascriptTransformer(successScript, Map.of())) {
            var result = testTransformer.transformJson(null);
            Assertions.assertInstanceOf(List.class, result);
            Assertions.assertEquals(List.of("output"), result);
            Assertions.assertEquals("com.oracle.truffle.polyglot.PolyglotList", result.getClass().getName());
        }

        // Script that returns a rejected Promise
        var errorScript = "((ignored) => (map) => {" +
                "return new Promise((resolve, reject) => {" +
                "reject(new Error('Test Error'));" +
                "});" +
                "});";

        // Test error case
        try (var testTransformer = new JavascriptTransformer(errorScript, Map.of())) {
            var exception = Assertions.assertThrows(Exception.class, () -> {
                testTransformer.transformJson(null);
            });
            Assertions.assertTrue(exception.getMessage().contains("Test Error"), "Exception should contain the error message");
            Assertions.assertEquals(RuntimeException.class, exception.getCause().getClass());
        }
    }
}
