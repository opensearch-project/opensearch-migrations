package org.opensearch.migrations.transform;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Slf4j
public class RouteTest {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> doRouting(Map<String, Object> flags, Map<String, Object> inputDoc) {
        log.atInfo().setMessage("parsed flags: {}").addArgument(flags).log();
        final var template = "" +
            "{%- macro doDefault(ignored_input) -%}" +
            "  {}" +
            "{%- endmacro -%}\n" +

            "{% macro echoFirstMatch(matches, input) %}\n" +
            "  { \"matchedVal\": \"{{ matches['group1'] }}\"}" +
            "{% endmacro %}" +
            "{% macro echoFirstMatchAgain(matches, input) %}\n" +
            "  { \"again\": \"{{ matches['group1'] }}\"}" +
            "{% endmacro %}" +
            "{% macro switchStuff(matches, input) %}\n" +
            "  {% set swapped_list = [input.stuff[1], input.stuff[0]] %}" +
            "  {% set input = input + {'stuff': swapped_list} %}" +
            "  {{ input | tojson }} " +
            "{% endmacro %}" +

            "{%- import \"common/route.j2\" as rscope -%}" +
            "{{- rscope.route(source, source.label, flags, 'doDefault'," +
            "  [" +
            "    ('Thing_A(.*)',        'echoFirstMatch',      'matchA')," +
            "    ('Thing_A(.*)', 'echoFirstMatchAgain', 'matchA')," + // make sure that we don't get duplicate results
            "    ('B(.*)',       'switchStuff',         'matchB')" +
            "  ])" +
            "-}}";

            var transformed = new JinjavaTransformer(template,
            src -> flags == null ? Map.of("source", inputDoc) : Map.of("source", inputDoc, "flags", flags));
        return transformed.transformJson(inputDoc);
    }

    @Test
    public void test() throws IOException {
        var flagAOff = Map.of(
            "matchA", false,
            "matchB", (Object) true);
        var docA = Map.of(
            "label", "Thing_A_and more!",
            "stuff", Map.of(
                "inner1", "data1",
                "inner2", "data2"
            ));
        var docB = Map.of(
            "label", "B-hive",
            "stuff", List.of(
                "data1",
                "data2"
            ));
        {
            var resultMap = doRouting(null, docA);
            Assertions.assertEquals(1, resultMap.size());
            Assertions.assertEquals("_and more!", resultMap.get("matchedVal"));
        }
        {
            var resultMap = doRouting(flagAOff, docA);
            Assertions.assertTrue(resultMap.isEmpty());
        }
        {
            var resultMap = doRouting(flagAOff, docB);
            Assertions.assertEquals("{\"label\":\"B-hive\",\"stuff\":[\"data2\",\"data1\"]}",
                objectMapper.writeValueAsString(new TreeMap<>(resultMap)));
        }
    }
}
