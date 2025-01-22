package org.opensearch.migrations.transform;

import java.time.Duration;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

@Slf4j
public class JavascriptTransformerTest {

    private static final String INIT_SCRIPT = "((context) => (document) => ({docSize: Object.keys(document).length+2 }))";
    private static final String INIT_SCRIPT_2 =
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
}
