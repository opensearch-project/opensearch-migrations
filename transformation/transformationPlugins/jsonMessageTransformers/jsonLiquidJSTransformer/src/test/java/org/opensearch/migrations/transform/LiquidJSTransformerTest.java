package org.opensearch.migrations.transform;

import java.time.Duration;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;


@Slf4j
class LiquidJsTransformerTest {

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
    public void testLiquidPerformance() throws Exception {
        var testTransformer = new LiquidJSTransformer("" +
//            "{% assign capitalized_map = {} %}\n" +
            "{\n" +
            "{%- for item in document -%}\n" +
            "  {%- assign key = item[0] | upcase -%}\n" +
            "  {%- assign value = item[1] | upcase -%}\n" +
            "  {{- item[0] | upcase | json }}: {{ item[1] | upcase | json -}}" +
            "{%- endfor -%}\n" +
            "}",
//            "{{ capitalized_map | json }}",
            incoming -> Map.of("document", incoming));

        var testDoc = Map.of("hi", (Object)"world");
        for (int j=0; j<5; ++j) {
            var start = System.nanoTime();
            var count = 0;
            for (int i = 0; i < 1000; ++i) {
                count += testTransformer.transformJson(testDoc).size();
            }
            log.atInfo().setMessage("Run {}: {}")
                .addArgument(j)
                .addArgument(Duration.ofNanos(System.nanoTime()-start)).log();
        }
    }

    @Test
    public void testInlinedScriptPerformance() throws Exception {
        var testTransformer = new LiquidJSTransformer("",
            incoming -> Map.of("document", incoming));

        var testDoc = Map.of("hi", (Object)"world");
        for (int j=0; j<20; ++j) {
            var start = System.nanoTime();
            var count = 0;
            for (int i = 0; i < 1000; ++i) {
                count += testTransformer.runJavascript(testDoc).length();
            }
            log.atInfo().setMessage("Run {}: {}")
                .addArgument(j)
                .addArgument(Duration.ofNanos(System.nanoTime()-start)).log();
        }
    }

//
//    @Test
//    public void debugLoggingWorks() throws Exception {
//        try (var closeableLogSetup = new CloseableLogSetup(LogFunction.class.getName())) {
//            final String FIRST_LOG_VAL = "LOGGED_VALUE=16";
//            final String SECOND_LOG_VAL = "next one";
//            final String THIRD_LOG_VAL = "LAST";
//
//            var indexTypeMappingRewriter = new JinjavaTransformer("" +
//                "{{ log_value_and_return('ERROR', log_value_and_return('ERROR', '" + FIRST_LOG_VAL + "', '" + SECOND_LOG_VAL + "'), '') }}" +
//                "{{ log_value('ERROR', '" + THIRD_LOG_VAL + "') }} " +
//                "{}",
//                request -> Map.of("request", request),
//                new JinjavaConfig(null,
//                    Map.of("hello", "{%- macro hello() -%}{\"hi\": \"world\"}{%- endmacro -%}\n")));
//
//            var resultObj = indexTypeMappingRewriter.transformJson(Map.of());
//            var resultStr = OBJECT_MAPPER.writeValueAsString(resultObj);
//            Assertions.assertEquals("{}", resultStr);
//
//            var logEvents = closeableLogSetup.getLogEvents();
//            Assertions.assertEquals(String.join("\n", new String[]{FIRST_LOG_VAL, SECOND_LOG_VAL, THIRD_LOG_VAL}),
//                logEvents.stream().collect(Collectors.joining("\n")));
//        }
//    }
}
