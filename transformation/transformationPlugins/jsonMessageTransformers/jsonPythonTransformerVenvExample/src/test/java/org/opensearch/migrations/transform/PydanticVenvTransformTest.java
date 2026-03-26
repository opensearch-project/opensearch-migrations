package org.opensearch.migrations.transform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * End-to-end test of the customer workflow for Python transforms with pip packages.
 *
 * <p>Before this test runs, Gradle tasks execute the exact customer steps:
 * <ol>
 *   <li>{@code graalpy -m venv} — creates a GraalPy virtual environment</li>
 *   <li>{@code pip install pydantic} — installs a pip package into the venv</li>
 *   <li>{@code tar czf venv.tar.gz} — packages the venv for distribution</li>
 * </ol>
 *
 * <p>This test then passes the tarball as {@code pythonModulePath} and the
 * custom_transform entry_point.py as the script — exactly as a customer would
 * configure their transformer.
 */
public class PydanticVenvTransformTest {

    @Test
    @SuppressWarnings("unchecked")
    public void testPydanticTransformViaTarball() throws Exception {
        var tarball = System.getProperty("pydantic.venv.tarball");
        var customTransformDir = System.getProperty("custom.transform.dir");

        var provider = new JsonPythonTransformerProvider();
        var config = Map.of(
            "initializationScriptFile", Path.of(customTransformDir, "entry_point.py").toString(),
            "bindingsObject", "{\"index_rewrites\": [{\"source_prefix\": \"logs-\", \"target_prefix\": \"migrated-\"}], \"add_fields\": {\"migrated\": true}}",
            "pythonModulePath", tarball
        );

        try (var transformer = provider.createTransformer(config)) {
            var batch = new ArrayList<Object>();
            batch.add(makeDoc("logs-2024.01", "doc1", Map.of("message", "hello")));
            batch.add(makeDoc("metrics-cpu", "doc2", Map.of("value", 42)));

            var result = (List<Map<String, Object>>) transformer.transformJson(batch);

            // First doc: index rewritten, fields added
            var doc1Op = (Map<String, Object>) result.get(0).get("operation");
            var doc1Body = (Map<String, Object>) result.get(0).get("document");
            assertThat(doc1Op.get("_index"), equalTo("migrated-2024.01"));
            assertThat(doc1Body.get("migrated"), equalTo(true));
            assertThat(doc1Body.get("message"), equalTo("hello"));

            // Second doc: no index rewrite (different prefix), but fields still added
            var doc2Op = (Map<String, Object>) result.get(1).get("operation");
            var doc2Body = (Map<String, Object>) result.get(1).get("document");
            assertThat(doc2Op.get("_index"), equalTo("metrics-cpu"));
            assertThat(doc2Body.get("migrated"), equalTo(true));
        }
    }

    private static Map<String, Object> makeDoc(String index, String id, Map<String, Object> fields) {
        var op = new HashMap<String, Object>();
        op.put("_index", index);
        op.put("_id", id);
        var doc = new HashMap<String, Object>();
        doc.put("operation", op);
        doc.put("document", new HashMap<>(fields));
        return doc;
    }
}
