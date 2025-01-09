package org.opensearch.migrations.transform;

import java.time.Duration;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

@Slf4j
public class JavascriptTransformerTest {

    private static final String INIT_SCRIPT = "";
    private static final String INVOCATION_SCRIPT = "({docSize: Object.keys(document).length+2 })";

    @Test
    @SuppressWarnings("unchecked")
    public void testInlinedScriptPerformance() throws Exception {
        var testTransformer = new JavascriptTransformer(INIT_SCRIPT, INVOCATION_SCRIPT,
            incoming -> Map.of("document", incoming));

        var testDoc = Map.of("hi", (Object)"world");
        for (int j=0; j<20; ++j) {
            var start = System.nanoTime();
            var count = 0;
            for (int i = 0; i < 1000; ++i) {
                count += ((Map<String, Object>) testTransformer.transformJson(testDoc)).size();
            }
            log.atInfo().setMessage("Run {}: {}")
                .addArgument(j)
                .addArgument(Duration.ofNanos(System.nanoTime()-start)).log();
        }
    }
}
