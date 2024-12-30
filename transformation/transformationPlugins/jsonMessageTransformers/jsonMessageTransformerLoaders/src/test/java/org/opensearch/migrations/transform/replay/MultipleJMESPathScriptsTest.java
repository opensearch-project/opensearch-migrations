package org.opensearch.migrations.transform.replay;

import java.util.StringJoiner;

import org.opensearch.migrations.transform.TransformationLoader;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MultipleJMESPathScriptsTest {
    private static final String EXCISE_SCRIPT =
        "{\\\"method\\\": method,\\\"URI\\\": URI,\\\"headers\\\":headers,\\\"payload\\\":"
            + "{\\\"inlinedJsonBody\\\":{\\\"mappings\\\": payload.inlinedJsonBody.mappings.oldType}}}";
    private static final String HOSTNAME_SCRIPT = "{\\n  \\\"method\\\": method,\\n  \\\"URI\\\": URI,\\n  "
        + "\\\"headers\\\": {\\\"host\\\": \\\"localhost\\\"},\\n  \\\"payload\\\": payload\\n}";
    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testTwoScripts() throws Exception {
        var aggregateScriptJoiner = new StringJoiner(",\n", "[", "]");
        for (var script : new String[] { EXCISE_SCRIPT, HOSTNAME_SCRIPT }) {
            aggregateScriptJoiner.add("{\"JsonJMESPathTransformerProvider\": { \"script\": \"" + script + "\"}}");
        }

        var aggregateScriptString = aggregateScriptJoiner.toString();
        var toNewHostTransformer = new TransformationLoader().getTransformerFactoryLoader(
            "localhost",
            null,
            aggregateScriptString
        );
        var origDoc = JsonTransformerTest.parseStringAsJson(mapper, JsonTransformerTest.TEST_INPUT_REQUEST);
        Object newDoc = toNewHostTransformer.transformJson(origDoc);

        final String TEST_OUTPUT_REQUEST = "{\n"
            + "    \"method\": \"PUT\",\n"
            + "    \"URI\": \"/oldStyleIndex\",\n"
            + "  \"headers\": {\n"
            + "    \"host\": \"localhost\"\n"
            + "  },\n"
            + "    \"payload\": {\n"
            + "        \"inlinedJsonBody\": {\n"
            + "            \"mappings\": {\n"
            + "                \"properties\": {\n"
            + "                    \"field1\": {\n"
            + "                        \"type\": \"text\"\n"
            + "                    },\n"
            + "                    \"field2\": {\n"
            + "                        \"type\": \"keyword\"\n"
            + "                    }\n"
            + "                }\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "}";
        Assertions.assertEquals(
            JsonTransformerTest.normalize(mapper, TEST_OUTPUT_REQUEST),
            JsonTransformerTest.emitJson(mapper, newDoc)
        );
    }
}
