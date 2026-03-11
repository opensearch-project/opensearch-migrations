package org.opensearch.migrations.transform;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

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
    public void testCreateTransformer_missingBindings() throws Exception {
        var config = Map.of(
            "initializationScript", "");
        var exception = assertThrows(IllegalArgumentException.class, () -> provider.createTransformer(config));
        assertThat(exception.getMessage(), containsString("Configuration missing required key: bindingsObject."));
    }

    @Test
    public void testCreateTransformer_missingScript() throws Exception {
        var config = Map.of(
            "bindingsObject", "{}");
        var exception = assertThrows(IllegalArgumentException.class, () -> provider.createTransformer(config));
        assertThat(exception.getMessage(),
            containsString("One of {\"initializationScriptFile\",\"initializationScript\",\"initializationResourcePath\"} must be provided."));
    }
}
