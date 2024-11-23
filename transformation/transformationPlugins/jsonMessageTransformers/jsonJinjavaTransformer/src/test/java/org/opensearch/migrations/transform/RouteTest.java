package org.opensearch.migrations.transform;

import java.io.IOException;
import java.util.Map;

import org.opensearch.migrations.transform.flags.FeatureFlags;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Slf4j
public class RouteTest {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final static String DEFAULT_RESPONSE = "{ \"default\": {}}";

    public Map<String, Object> doRouting(Map<String, Object> flags, Map<String, Object> inputDoc) {
        log.atInfo().setMessage("parsed flags: {}").addArgument(flags).log();
        final var template = "" +
            "{%- macro doDefault() -%}" +
            "  {}" +
            "{%- endmacro -%}\n" +

            "{%- macro matchIt(doc) -%}" +
            "  {{ doc.label | regex_capture('Thing_A(.*)') }}" +
            "{%- endmacro -%}\n" +
            "{% macro handleIt(matches) %}\n" +
            "{% for key, value in matches.items() %}\n" + // TODO - this is a string, not an object!

//            "  {{ key }}: {{ value }}<br>\n" +
            "{% endfor %}" +
//            " - {{ matches['group'] }} - " +
//            "  { \"matchedVal\": \"{{ matches['group1'] }}\"}" +
            "{% endmacro %}" +

            "    {% call doDefault() %}" +
            "        {{input}}" +
            "    {% endcall %}" +

            "\n" +
            "{%- import \"common/route.j2\" as router -%}" +
            "{{- router.route(source, flags, doDefault," +
            "  [" +
            "    ('labelMatches', 'matchIt', 'handleIt')" +
            "  ])" +
            "-}}";

            var transformed = new JinjavaTransformer(template,
            src -> flags == null ? Map.of("source", inputDoc) : Map.of("source", inputDoc, "flags", flags));
        return transformed.transformJson(inputDoc);
    }


    @Test
    public void test() {
        var flags = Map.of(
            "first", false,
            "second", (Object) true);
        var inputDoc = Map.of(
            "label", "Thing_A_and more!",
            "stuff", Map.of(
                "inner1", "data1",
                "inner2", "data2"
            )
        );

        log.atInfo().setMessage("parsed flags: {}").addArgument(flags).log();
        final var template = "" +
            "{% macro table(predicate_output) %}\n" +
            "    {{Content of Macro 1\n}}" +
            "{% endmacro %}\n" +
            "\n";
        var transformed = new JinjavaTransformer(template,
            src -> flags == null ? Map.of("source", inputDoc) : Map.of("source", inputDoc, "flags", flags));
        var result = transformed.transformJson(inputDoc);
        System.out.println(result);
    }

    @Test
    public void testA() {
        var flagAOff = Map.of(
            "first", false,
            "second", (Object) true);
        var doc = Map.of(
            "label", "Thing_A_and more!",
            "stuff", Map.of(
                "inner1", "data1",
                "inner2", "data2"
            )
        );
        Assertions.assertEquals("_and more!", doRouting(null, doc).get("matchedVal"));
        Assertions.assertTrue(doRouting(flagAOff, doc).isEmpty());
    }
}
