package org.opensearch.migrations.transform.replay;

import java.util.Map;

import org.opensearch.migrations.transform.JsonKeysForHttpMessage;
import org.opensearch.migrations.transform.TransformationLoader;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MultipleJoltScriptsTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static Map<String, Object> parseAsMap(String contents) throws Exception {
        return mapper.readValue(contents.getBytes(), new TypeReference<>() {
        });
    }

    @Test
    public void testAddGzip() throws Exception {
        final var addGzip = "[{\"JsonJoltTransformerProvider\": { \"canned\": \"ADD_GZIP\" }}]";
        var toNewHostTransformer = new TransformationLoader().getTransformerFactoryLoader(
            "testhostname",
            null,
            addGzip
        );
        var origDocStr = SampleContents.loadSampleJsonRequestAsString();
        var origDoc = parseAsMap(origDocStr);
        Object newDoc = toNewHostTransformer.transformJson(origDoc);
        Assertions.assertEquals("testhostname", ((Map) ((Map) newDoc).get(JsonKeysForHttpMessage.HEADERS_KEY)).get("host"));
        Assertions.assertEquals("gzip", ((Map) ((Map) newDoc).get(JsonKeysForHttpMessage.HEADERS_KEY)).get("content-encoding"));
    }

    @Test
    public void testAddGzipAndCustom() throws Exception {
        final var addGzip = "["
            + "{\"JsonConditionalTransformerProvider\": ["
            + "   {\"JsonJMESPathPredicateProvider\": { \"script\": \"" + "URI == '/testindex/_search'" + "\"}},"
            + "   [{\"JsonJoltTransformerProvider\": { \"canned\": \"ADD_GZIP\" }}]"
            + "]},"
            + "{ \"JsonJoltTransformerProvider\":"
            + "  {\"script\": \n"
            + "    { \"operation\": \"modify-overwrite-beta\", \"spec\": "
            + "      { \"headers\": {\"newHeader\": \"newValue\"}}}}}"
            + "]";
        var toNewHostTransformer = new TransformationLoader().getTransformerFactoryLoader(
            "testhostname",
            null,
            addGzip
        );
        var origDocStr = SampleContents.loadSampleJsonRequestAsString();
        var origDoc = parseAsMap(origDocStr);
        Object newDoc = toNewHostTransformer.transformJson(origDoc);
        Assertions.assertEquals("testhostname", ((Map) ((Map) newDoc).get(JsonKeysForHttpMessage.HEADERS_KEY)).get("host"));
        var headers = (Map) ((Map) newDoc).get(JsonKeysForHttpMessage.HEADERS_KEY);
        Assertions.assertEquals("gzip", headers.get("content-encoding"));
        Assertions.assertEquals("newValue", headers.get("newHeader"));
    }

    @Test
    public void testExciseWhenPresent() throws Exception {
        var script =
            "[{ \"JsonJoltTransformerProvider\":\n" +
            "[\n" +
            "  {\n" +
            "    \"script\": {\n" +
            "      \"operation\": \"shift\",\n" +
            "      \"spec\": {\n" +
            "        \"payload\": {\n" +
            "          \"inlinedJsonBody\": {\n" +
            "            \"top\": {\n" +
            "              \"tagToExcise\": {\n" +
            "                \"*\": \"payload.inlinedJsonBody.top.&\" \n" +
            "              },\n" +
            "              \"*\": \"payload.inlinedJsonBody.top.&\"\n" +
            "            },\n" +
            "            \"*\": \"payload.inlinedJsonBody.&\"\n" +
            "          },\n" +
            "          \"*\": \"payload.&\"\n" +
            "        },\n" +
            "        \"*\": \"&\"\n" +
            "      }\n" +
            "    }\n" +
            "  }, \n" +
            " {\n" +
            "   \"script\": {\n" +
            "     \"operation\": \"modify-overwrite-beta\",\n" +
            "     \"spec\": {\n" +
            "       \"URI\": \"=split('/extraThingToRemove',@(1,&))\"\n" +
            "     }\n" +
            "  }\n" +
            " },\n" +
            " {\n" +
            "   \"script\": {\n" +
            "     \"operation\": \"modify-overwrite-beta\",\n" +
            "     \"spec\": {\n" +
            "       \"URI\": \"=join('',@(1,&))\"\n" +
            "     }\n" +
            "  }\n" +
            " }\n" +
            "]\n" +
            "}]";


        var excisingTransformer = new TransformationLoader().getTransformerFactoryLoader(
            "testhostname",
            null,
            script
        );
        var origDocStr = "{\n" +
            "  \"method\": \"PUT\",\n" +
            "  \"protocol\": \"HTTP/1.0\",\n" +
            "  \"URI\": \"/oldStyleIndex/extraThingToRemove/moreStuff\",\n" +
            "  \"headers\": {\n" +
            "    \"host\": \"127.0.0.1\"\n" +
            "  },\n" +
            "  \"payload\": {\n" +
            "    \"inlinedJsonBody\": {\n" +
            "      \"top\": {\n" +
            "        \"tagToExcise\": {\n" +
            "          \"properties\": {\n" +
            "            \"field1\": {\n" +
            "              \"type\": \"text\"\n" +
            "            },\n" +
            "            \"field2\": {\n" +
            "              \"type\": \"keyword\"\n" +
            "            }\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";
        var expectedDocStr = "{\"method\":\"PUT\",\"protocol\":\"HTTP/1.0\",\"URI\":\"/oldStyleIndex/moreStuff\",\"headers\":{\"host\":\"testhostname\"},\"payload\":{\"inlinedJsonBody\":{\"top\":{\"properties\":{\"field1\":{\"type\":\"text\"},\"field2\":{\"type\":\"keyword\"}}}}}}";
        var origDoc = parseAsMap(origDocStr);
        Object newDoc = excisingTransformer.transformJson(origDoc);
        var newAsStr = mapper.writeValueAsString(newDoc);
        Assertions.assertEquals(expectedDocStr, newAsStr);

        Object secondPassDoc = excisingTransformer.transformJson(newDoc);
        var secondPassDocAsStr = mapper.writeValueAsString(secondPassDoc);
        Assertions.assertEquals(expectedDocStr, secondPassDocAsStr);

        Assertions.assertEquals("testhostname", ((Map) ((Map) newDoc).get(JsonKeysForHttpMessage.HEADERS_KEY)).get("host"));
    }
}
