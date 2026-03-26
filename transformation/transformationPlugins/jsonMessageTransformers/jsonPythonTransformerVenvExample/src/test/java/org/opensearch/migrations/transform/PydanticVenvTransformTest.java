package org.opensearch.migrations.transform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.graalvm.python.embedding.GraalPyResources;
import org.graalvm.python.embedding.VirtualFileSystem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests the customer workflow: a Python transform that uses pydantic (a pip package)
 * loaded from an external venv via {@code pythonModulePath}.
 *
 * <p>The GraalPy Gradle plugin pre-installs pydantic at build time into the VFS.
 * This test extracts that VFS to a temp directory — simulating what a customer would
 * have after running {@code graalpy -m venv /opt/venv && /opt/venv/bin/pip install pydantic}.
 * It then passes that directory as {@code pythonModulePath} to the provider, exactly
 * as a customer would in their transformer config.
 */
public class PydanticVenvTransformTest {

    private static Path extractedVenv;
    private static Path scriptFile;

    @BeforeAll
    static void setUp() throws IOException {
        // Extract the build-time VFS (which contains pydantic) to a temp directory.
        // This simulates a customer's GraalPy venv with pip-installed packages.
        extractedVenv = Files.createTempDirectory("pydantic-venv-");
        var vfs = VirtualFileSystem.newBuilder()
            .allowHostIO(VirtualFileSystem.HostIO.READ)
            .build();
        GraalPyResources.extractVirtualFileSystemResources(vfs, extractedVenv);

        // Write the transform script to a temp file (simulates customer's entry_point.py)
        try (var is = PydanticVenvTransformTest.class.getClassLoader()
                .getResourceAsStream("pydantic_transform.py")) {
            scriptFile = Files.createTempFile("pydantic-transform-", ".py");
            Files.write(scriptFile, is.readAllBytes());
        }
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (scriptFile != null) Files.deleteIfExists(scriptFile);
        if (extractedVenv != null) {
            try (var walk = Files.walk(extractedVenv)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testPydanticTransformViaProvider() throws Exception {
        var provider = new JsonPythonTransformerProvider();
        var config = Map.of(
            "initializationScriptFile", scriptFile.toString(),
            "bindingsObject", "{\"index_rewrites\": [{\"source_prefix\": \"logs-\", \"target_prefix\": \"migrated-\"}], \"add_fields\": {\"migrated\": true}}",
            "pythonModulePath", extractedVenv.toString()
        );

        try (var transformer = provider.createTransformer(config)) {
            var op = new HashMap<String, Object>();
            op.put("_index", "logs-2024.01");
            op.put("_id", "doc1");

            var body = new HashMap<String, Object>();
            body.put("message", "hello");

            var doc = new HashMap<String, Object>();
            doc.put("operation", op);
            doc.put("document", body);

            var batch = new ArrayList<Object>();
            batch.add(doc);

            var result = (List<Map<String, Object>>) transformer.transformJson(batch);

            assertThat(result.size(), equalTo(1));
            var resultDoc = result.get(0);
            var resultOp = (Map<String, Object>) resultDoc.get("operation");
            var resultBody = (Map<String, Object>) resultDoc.get("document");

            assertThat(resultOp.get("_index"), equalTo("migrated-2024.01"));
            assertThat(resultOp.get("_id"), equalTo("doc1"));
            assertThat(resultBody.get("migrated"), equalTo(true));
            assertThat(resultBody.get("message"), equalTo("hello"));
        }
    }
}
