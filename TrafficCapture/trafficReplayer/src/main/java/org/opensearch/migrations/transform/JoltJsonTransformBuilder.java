package org.opensearch.migrations.transform;

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
public class JoltJsonTransformBuilder {

    public enum CANNED_OPERATIONS {
        ADD_GZIP("addGzip"),
        MAKE_CHUNKED("makeChunked"),
        ADD_ADMIN_AUTH("addAdminAuth"),
        PASS_THRU("passThru");

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
    public static final String ADD_AUTHORIZATION_HEADER_OPERATION = "addAdminAuth.jolt";
    public static final String RESOURCE_PASS_THRU_OPERATION = "passThru.jolt";

    ObjectMapper mapper = new ObjectMapper();
    List<Map<String,Object>> chainedSpec = new ArrayList<>();

    public Map<String, Object> loadResourceAsJson(String path) throws IOException {
        return loadResourceAsJson(mapper, path);
    }

    public static Map<String, Object> loadResourceAsJson(ObjectMapper mapper, String path) throws IOException {
        try (InputStream inputStream = JoltJsonTransformBuilder.class.getResourceAsStream(path)) {
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
        var joltTransformTemplate = parseSpecOperationFromResource(RESOURCE_HOST_SWITCH_OPERATION);
        var specJson = (Map<String, Object>) joltTransformTemplate.get("spec");
        var headersSpecJson = (Map<String, Object>) specJson.get("headers");
        headersSpecJson.put("host", targetClusterHostname);
        return joltTransformTemplate;
    }


    public Map<String, Object> getAddAuthorizationOperation(String authorizationHeader) {
        var joltTransformTemplate = parseSpecOperationFromResource(ADD_AUTHORIZATION_HEADER_OPERATION);
        var specJson = (Map<String, Object>) joltTransformTemplate.get("spec");
        var headersSpecJson = (Map<String, Object>) specJson.get("headers");
        headersSpecJson.put("authorization", authorizationHeader);
        return joltTransformTemplate;
    }


    public JoltJsonTransformBuilder addHostSwitchOperation(String hostname) {
        return addOperationObject(getHostSwitchOperation(hostname));
    }

    public JoltJsonTransformBuilder addAuthorizationOperation(String hostname) {
        return addOperationObject(getAddAuthorizationOperation(hostname));
    }

    public JoltJsonTransformBuilder addCannedOperation(CANNED_OPERATIONS operation) {
        return addCannedOperation(operation.toString() + ".jolt");
    }

    public JoltJsonTransformBuilder addCannedOperation(String resourceName) {
        return addOperationObject(parseSpecOperationFromResource(resourceName));
    }

    public JoltJsonTransformBuilder addOperationObject(Map<String, Object> stringObjectMap) {
        chainedSpec.add(stringObjectMap);
        return this;
    }

    public JsonTransformer build() {
        if (chainedSpec.size() == 0) {
            addCannedOperation(RESOURCE_PASS_THRU_OPERATION);
        }
        return new JoltJsonTransformer((List) chainedSpec);
    }
}
