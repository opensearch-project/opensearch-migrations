package org.opensearch.migrations.transform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class JsonTransformBuilder {

    public enum CANNED_OPERATIONS {
        ADD_GZIP("addGzip"), PASS_THRU("passThru");

        private final String value;

        CANNED_OPERATIONS(String s) {
            value = s;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /**
     * Visibility increased for testing
     */
    static final TypeReference<LinkedHashMap<String, Object>> TYPE_REFERENCE_FOR_MAP_TYPE =
            new TypeReference<LinkedHashMap<String, Object>>(){};
    public static final String RESOURCE_HOST_SWITCH_OPERATION = "hostSwitch.jolt";
    public static final String RESOURCE_PASS_THRU_OPERATION = "passThru.jolt";

    ObjectMapper mapper = new ObjectMapper();
    List<Map<String,Object>> chainedSpec = new ArrayList<>();

    public Map<String, Object> loadResourceAsJson(String path) throws IOException {
        return loadResourceAsJson(mapper, path);
    }

    public static Map<String, Object> loadResourceAsJson(ObjectMapper mapper, String path) throws IOException {
        try (InputStream inputStream = JsonTransformBuilder.class.getResourceAsStream(path)) {
            return mapper.readValue(inputStream, TYPE_REFERENCE_FOR_MAP_TYPE);
        }
    }
    private static final String SPEC_JSON_KEYNAME = "spec";

    @SneakyThrows
    private List parseSpecListFromTransformResource(String resource) {
        var jsonObject = loadResourceAsJson("jolt/transformations/"+resource);
        return (List) jsonObject.get(SPEC_JSON_KEYNAME);
    }

    @SneakyThrows
    private Map<String, Object> parseSpecOperationFromResource(String resource) {
        return loadResourceAsJson("/jolt/operations/"+resource);
    }

    private Map<String, Object> getHostSwitchOperation(String targetClusterHostname) {
        var jsonHostSwitchTemplateJson = parseSpecOperationFromResource(RESOURCE_HOST_SWITCH_OPERATION);
        var specJson = (Map<String, Object>) jsonHostSwitchTemplateJson.get("spec");
        var headersSpecJson = (Map<String, Object>) specJson.get("headers");
        headersSpecJson.put("host", targetClusterHostname);
        return jsonHostSwitchTemplateJson;
    }

    public JsonTransformBuilder addHostSwitchOperation(String hostname) {
         return addOperationObject(getHostSwitchOperation(hostname));
    }

    public JsonTransformBuilder addCannedOperation(CANNED_OPERATIONS operation) {
        return addCannedOperation(operation.toString() + ".jolt");
    }

    public JsonTransformBuilder addCannedOperation(String resourceName) {
        return addOperationObject(parseSpecOperationFromResource(resourceName));
    }

    public JsonTransformBuilder addOperationObject(Map<String, Object> stringObjectMap) {
        chainedSpec.add(stringObjectMap);
        return this;
    }

    public JsonTransformer build() {
        if (chainedSpec.size() == 0) {
            addCannedOperation(RESOURCE_PASS_THRU_OPERATION);
        }
        return new JsonTransformer((List) chainedSpec);
    }
}
