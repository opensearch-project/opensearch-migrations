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

public class JsonJSTransformerProviderTest {
    private static final String SIMPLE_JS_TRANSFORM = "\n" + //
            "function main(context) {\n" + //
            "  return (document) => {\n" + //
            "    document.set(\"modified\", true);\n" + //
            "    return document;\n" + //
            "  };\n" + //
            "}\n" + //
            "(() => main)();";
    private static final Map<String, String> TEST_DOC = Map.of(
            "name", "test-doc",
            "type", "document");

    private File tempScriptFile;
    private JsonJSTransformerProvider provider;

    @BeforeEach
    public void before() throws Exception {
        tempScriptFile = File.createTempFile("json-js-transformation-provider", ".js");
        provider = new JsonJSTransformerProvider();
    }

    @AfterEach
    public void after() {
        tempScriptFile.delete();
    }

    @Test
    public void testCreateTransformer_scriptFile() throws Exception {
        Files.writeString(tempScriptFile.toPath(), SIMPLE_JS_TRANSFORM);

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
                "initializationScript", SIMPLE_JS_TRANSFORM);
        var transformer = provider.createTransformer(config);
        var result = (Map) transformer.transformJson(new HashMap<>(TEST_DOC));

        assertThat(result.getOrDefault("modified", null), equalTo(true));
        assertThat(result.size(), equalTo(TEST_DOC.size() + 1));
    }

    @Test
    public void testCreateTransformer_invalidScript() throws Exception {
        var config = Map.of(
                "bindingsObject", "{}",
                "initializationScript", "");
        var exception = assertThrows(UnsupportedOperationException.class, () -> provider.createTransformer(config));
        assertThat(exception.getMessage(), containsString("Unsupported operation Value.execute(Object...)"));
    }

    @Test
    public void testCreateTransformer_missingBindings() throws Exception {
        var config = Map.of(
                "initializationScript", "");
        var exception = assertThrows(IllegalArgumentException.class, () -> provider.createTransformer(config));
        assertThat(exception.getMessage(), containsString("Configuration missing required key: bindingsObject."));
    }

    @Test
    public void testCreateTransformer_invalidBindings() throws Exception {
        var config = Map.of(
                "bindingsObject", Map.of(),
                "initializationScript", "");
        var exception = assertThrows(IllegalArgumentException.class, () -> provider.createTransformer(config));
        assertThat(exception.getMessage(), containsString("Failed to parse the bindingsObject."));
    }

    @Test
    public void testCreateTransformer_missingScript() throws Exception {
        var config = Map.of(
                "bindingsObject", "{}");
        var exception = assertThrows(IllegalArgumentException.class, () -> provider.createTransformer(config));
        assertThat(exception.getMessage(),
                containsString("One of {\"initializationScriptFile\",\"initializationScript\"} must be provided."));
    }

    @Test
    public void testCreateTransformer_tooManyScriptSources() throws Exception {
        var config = Map.of(
                "bindingsObject", "{}",
                "initializationScript", "",
                "initializationScriptFile", tempScriptFile.getAbsolutePath());
        var exception = assertThrows(IllegalArgumentException.class, () -> provider.createTransformer(config));
        assertThat(exception.getMessage(), containsString(
                "Unable to use both parameters at the same time, {\"initializationScriptFile\",\"initializationScript\"}. "));
    }

    @Test
    public void testCreateTransformer_invalidFilePath() throws Exception {
        var config = Map.of(
                "bindingsObject", "{}",
                "initializationScriptFile", "");
        var exception = assertThrows(IllegalArgumentException.class, () -> provider.createTransformer(config));
        assertThat(exception.getMessage(), containsString("Failed to load script file ''."));
    }
}
