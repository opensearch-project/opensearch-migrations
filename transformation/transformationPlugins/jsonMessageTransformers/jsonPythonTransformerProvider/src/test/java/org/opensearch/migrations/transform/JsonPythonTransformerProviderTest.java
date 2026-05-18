package org.opensearch.migrations.transform;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class JsonPythonTransformerProviderTest {
    private static final String SIMPLE_PYTHON_TRANSFORM =
        "def main(context):\n" +
        "    def transform(document):\n" +
        "        document['modified'] = True\n" +
        "        return document\n" +
        "    return transform\n" +
        "main";
    private static final String CONTEXT_PYTHON_TRANSFORM =
        "def main(context):\n" +
        "    def transform(document):\n" +
        "        document['configured'] = context['configured']\n" +
        "        return document\n" +
        "    return transform\n" +
        "main";
    private static final Map<String, String> TEST_DOC = Map.of(
        "name", "test-doc",
        "type", "document");

    private File tempScriptFile;
    private JsonPythonTransformerProvider provider;

    @BeforeEach
    public void before() throws Exception {
        tempScriptFile = File.createTempFile("json-python-transformation-provider", ".py");
        provider = new JsonPythonTransformerProvider();
    }

    @AfterEach
    public void after() {
        tempScriptFile.delete();
    }

    @Test
    public void testCreateTransformer_scriptFile() throws Exception {
        Files.writeString(tempScriptFile.toPath(), SIMPLE_PYTHON_TRANSFORM);

        var config = Map.of(
            "bindingsObject", "{}",
            "initializationScriptFile", tempScriptFile.getAbsolutePath());
        var transformer = provider.createTransformer(config);
        var result = (Map) transformer.transformJson(new HashMap<>(TEST_DOC));

        assertThat(result.getOrDefault("modified", null), equalTo(true));
        assertThat(result.size(), equalTo(TEST_DOC.size() + 1));
    }

    @Test
    public void testCreateTransformer_inlineScript() throws Exception {
        var config = Map.of(
            "bindingsObject", "{}",
            "initializationScript", SIMPLE_PYTHON_TRANSFORM);
        var transformer = provider.createTransformer(config);
        var result = (Map) transformer.transformJson(new HashMap<>(TEST_DOC));

        assertThat(result.getOrDefault("modified", null), equalTo(true));
        assertThat(result.size(), equalTo(TEST_DOC.size() + 1));
    }

    @Test
    public void testCreateTransformer_nullConfig() {
        var exception = assertThrows(IllegalArgumentException.class, () -> provider.createTransformer(null));
        assertThat(exception.getMessage(), containsString("Configuration must not be null or empty."));
    }

    @Test
    public void testCreateTransformer_nonMapConfig() {
        var exception = assertThrows(IllegalArgumentException.class, () -> provider.createTransformer("not-a-map"));
        assertThat(exception.getMessage(), containsString("expects the incoming configuration to be a Map"));
    }

    @Test
    public void testCreateTransformer_missingBindings() {
        var config = Map.of(
            "initializationScript", SIMPLE_PYTHON_TRANSFORM);
        var transformer = provider.createTransformer(config);
        var result = (Map) transformer.transformJson(new HashMap<>(TEST_DOC));

        assertThat(result.getOrDefault("modified", null), equalTo(true));
    }

    @Test
    public void testCreateTransformer_objectBindings() {
        var config = Map.of(
            "bindingsObject", Map.of("configured", "from-object"),
            "initializationScript", CONTEXT_PYTHON_TRANSFORM);
        var transformer = provider.createTransformer(config);
        var result = (Map) transformer.transformJson(new HashMap<>(TEST_DOC));

        assertThat(result.getOrDefault("configured", null), equalTo("from-object"));
    }

    @Test
    public void testCreateTransformer_invalidBindings() {
        var config = Map.of(
            "bindingsObject", "{",
            "initializationScript", "");
        var exception = assertThrows(IllegalArgumentException.class, () -> provider.createTransformer(config));
        assertThat(exception.getMessage(), containsString("Failed to parse the bindingsObject."));
    }

    @Test
    public void testCreateTransformer_missingScript() {
        var config = Map.of(
            "bindingsObject", "{}");
        var exception = assertThrows(IllegalArgumentException.class, () -> provider.createTransformer(config));
        assertThat(exception.getMessage(),
            containsString("One of {\"initializationScriptFile\",\"initializationScript\",\"initializationResourcePath\"} must be provided."));
    }

    @Test
    public void testCreateTransformer_tooManyScriptSources() throws Exception {
        Files.writeString(tempScriptFile.toPath(), SIMPLE_PYTHON_TRANSFORM);
        var config = Map.of(
            "bindingsObject", "{}",
            "initializationScript", SIMPLE_PYTHON_TRANSFORM,
            "initializationScriptFile", tempScriptFile.getAbsolutePath());
        var exception = assertThrows(IllegalArgumentException.class, () -> provider.createTransformer(config));
        assertThat(exception.getMessage(), containsString(
            "Unable to use both parameters at the same time"));
    }

    @Test
    public void testCreateTransformer_invalidFilePath() {
        var config = Map.of(
            "bindingsObject", "{}",
            "initializationScriptFile", "/nonexistent/path/script.py");
        var exception = assertThrows(IllegalArgumentException.class, () -> provider.createTransformer(config));
        assertThat(exception.getMessage(), containsString("Failed to load script file"));
    }

    @Test
    public void testCreateTransformer_resourceNotFound() {
        var config = Map.of(
            "bindingsObject", "{}",
            "initializationResourcePath", "nonexistent-resource.py");
        var exception = assertThrows(IllegalArgumentException.class, () -> provider.createTransformer(config));
        assertThat(exception.getMessage(), containsString("Resource not found: nonexistent-resource.py"));
    }

    @Test
    public void testCreateTransformer_resourcePath() throws Exception {
        var config = Map.of(
            "bindingsObject", "{}",
            "initializationResourcePath", "test-transform.py");
        var transformer = provider.createTransformer(config);
        var result = (Map) transformer.transformJson(new HashMap<>(TEST_DOC));

        assertThat(result.getOrDefault("transformed_by", null), equalTo("resource_script"));
    }

    @Test
    public void testCreateTransformer_invalidPythonModulePath() {
        var config = Map.of(
            "bindingsObject", "{}",
            "initializationScript", SIMPLE_PYTHON_TRANSFORM,
            "pythonModulePath", "/nonexistent/venv/path");
        var exception = assertThrows(IllegalArgumentException.class, () -> provider.createTransformer(config));
        assertThat(exception.getMessage(), containsString("must be a directory or a .tar.gz file"));
    }

    @Test
    public void testCreateTransformer_resourceAndFileConflict() throws Exception {
        Files.writeString(tempScriptFile.toPath(), SIMPLE_PYTHON_TRANSFORM);
        var config = Map.of(
            "bindingsObject", "{}",
            "initializationScriptFile", tempScriptFile.getAbsolutePath(),
            "initializationResourcePath", "test-transform.py");
        var exception = assertThrows(IllegalArgumentException.class, () -> provider.createTransformer(config));
        assertThat(exception.getMessage(), containsString("Unable to use both parameters at the same time"));
    }

    @Test
    public void testCreateTransformer_scriptFileCanImportSiblingModule() throws Exception {
        var tempDir = Files.createTempDirectory("json-python-transform-siblings");
        var helperFile = tempDir.resolve("helper.py");
        var entryFile = tempDir.resolve("entry.py");
        try {
            Files.writeString(helperFile,
                "def mark(document):\n" +
                "    document['from_helper'] = True\n" +
                "    return document\n");
            Files.writeString(entryFile,
                "from helper import mark\n\n" +
                "def main(context):\n" +
                "    return mark\n" +
                "main");

            var config = Map.of(
                "initializationScriptFile", entryFile.toString());
            var transformer = provider.createTransformer(config);
            var result = (Map) transformer.transformJson(new HashMap<>(TEST_DOC));

            assertThat(result.getOrDefault("from_helper", null), equalTo(true));
        } finally {
            Files.deleteIfExists(entryFile);
            Files.deleteIfExists(helperFile);
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testCreateTransformer_tarGzPythonModulePath() throws Exception {
        // Create a tarball with a single top-level "my-venv/" directory
        var tarGzFile = File.createTempFile("test-venv", ".tar.gz");
        try (var fos = new FileOutputStream(tarGzFile);
             var gos = new GzipCompressorOutputStream(fos);
             var tos = new TarArchiveOutputStream(gos)) {
            var dirEntry = new TarArchiveEntry("my-venv/");
            tos.putArchiveEntry(dirEntry);
            tos.closeArchiveEntry();

            var content = "marker".getBytes();
            var fileEntry = new TarArchiveEntry("my-venv/pyvenv.cfg");
            fileEntry.setSize(content.length);
            tos.putArchiveEntry(fileEntry);
            tos.write(content);
            tos.closeArchiveEntry();
        }

        var config = Map.of(
            "bindingsObject", "{}",
            "initializationScript", SIMPLE_PYTHON_TRANSFORM,
            "pythonModulePath", tarGzFile.getAbsolutePath());
        var transformer = provider.createTransformer(config);
        var result = (Map<String, Object>) transformer.transformJson(new HashMap<>(TEST_DOC));

        assertThat(result.getOrDefault("modified", null), equalTo(true));
        tarGzFile.delete();
    }
}
