package org.opensearch.migrations.transform;

import java.util.HashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

@Slf4j
public class PythonTransformerTest {

    private static final String SIMPLE_SCRIPT =
        "def main(context):\n" +
        "    def transform(document):\n" +
        "        return {'docSize': len(document) + 2}\n" +
        "    return transform\n" +
        "main";

    @Test
    @SuppressWarnings("unchecked")
    public void testSimpleTransformation() throws Exception {
        try (var transformer = new PythonTransformer(SIMPLE_SCRIPT, Map.of("document", Map.of()))) {
            var testDoc = Map.of("hi", (Object) "world");
            var result = (Map<String, Object>) transformer.transformJson(testDoc);
            Assertions.assertEquals(3, result.get("docSize"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testMapModification() throws Exception {
        var script =
            "def transform(document):\n" +
            "    document['modified'] = True\n" +
            "    return document\n" +
            "transform";
        try (var transformer = new PythonTransformer(script, null)) {
            var map = new HashMap<String, Object>();
            map.put("foo", "bar");
            var result = (Map<String, Object>) transformer.transformJson(map);
            Assertions.assertEquals(true, result.get("modified"));
            Assertions.assertEquals("bar", result.get("foo"));
        }
    }

    @Test
    public void testPythonPrintCapturedByLoggingStream() throws Exception {
        var script =
            "def transform(document):\n" +
            "    print('hello from python')\n" +
            "    return document\n" +
            "transform";
        try (var transformer = new PythonTransformer(script, null)) {
            var result = transformer.transformJson(new HashMap<>(Map.of("key", "value")));
            Assertions.assertNotNull(result);
        }
    }

    @Test
    public void testLoggingOutputStream() throws Exception {
        var logger = LoggerFactory.getLogger("TestLogger");
        try (var stream = new PythonTransformer.LoggingOutputStream(logger, Level.INFO)) {
            stream.write("test message".getBytes(), 0, "test message".length());
            stream.flush();
            // Flush with empty buffer should not throw
            stream.flush();
        }
    }
}
