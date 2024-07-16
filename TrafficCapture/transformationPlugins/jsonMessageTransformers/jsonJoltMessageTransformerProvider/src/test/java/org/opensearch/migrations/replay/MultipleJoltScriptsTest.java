package org.opensearch.migrations.replay;

import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

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
        var newDoc = toNewHostTransformer.transformJson(origDoc);
        Assertions.assertEquals("testhostname", ((Map) newDoc.get(JsonKeysForHttpMessage.HEADERS_KEY)).get("host"));
        Assertions.assertEquals("gzip", ((Map) newDoc.get(JsonKeysForHttpMessage.HEADERS_KEY)).get("content-encoding"));
    }

    @Test
    public void testAddGzipAndCustom() throws Exception {
        final var addGzip = "["
            + "{\"JsonJoltTransformerProvider\": { \"canned\": \"ADD_GZIP\" }},"
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
        var newDoc = toNewHostTransformer.transformJson(origDoc);
        Assertions.assertEquals("testhostname", ((Map) newDoc.get(JsonKeysForHttpMessage.HEADERS_KEY)).get("host"));
        var headers = (Map) newDoc.get(JsonKeysForHttpMessage.HEADERS_KEY);
        Assertions.assertEquals("gzip", headers.get("content-encoding"));
        Assertions.assertEquals("newValue", headers.get("newHeader"));
    }

}
