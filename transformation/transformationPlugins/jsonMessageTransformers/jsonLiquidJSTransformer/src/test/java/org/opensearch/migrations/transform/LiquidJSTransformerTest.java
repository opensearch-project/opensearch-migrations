package org.opensearch.migrations.transform;

import java.time.Duration;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;


@Slf4j
class LiquidJSTransformerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    public void testInlinedScript() throws Exception {
        var testTransformer = new LiquidJSTransformer("" +
            "{\n" +
            "{%- for item in document -%}\n" +
            "  {{- item[0] | upcase | json }}: {{ item[1] | upcase | json -}}" +
            "{%- endfor -%}\n" +
            "}",
            incoming -> Map.of("document", incoming));

        var resultObj = testTransformer.transformJson(Map.of("hi", "world"));
        var resultStr = OBJECT_MAPPER.writeValueAsString(resultObj);
        Assertions.assertEquals("{\"HI\":\"WORLD\"}", resultStr);
    }


    @Test
    @SuppressWarnings("unchecked")
    public void testLiquidPerformance() throws Exception {
        var testTransformer = new LiquidJSTransformer("" +
            "{\n" +
            "{%- for item in document -%}\n" +
            "  {%- assign key = item[0] | upcase -%}\n" +
            "  {%- assign value = item[1] | upcase -%}\n" +
            "  {{- item[0] | upcase | json }}: {{ item[1] | upcase | json -}}" +
            "{%- endfor -%}\n" +
            "}",
            incoming -> Map.of("document", incoming));

        var testDoc = Map.of("hi", (Object)"world");
        for (int j=0; j<5; ++j) {
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
