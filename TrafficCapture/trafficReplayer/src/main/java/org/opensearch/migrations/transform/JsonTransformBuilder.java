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
        // TODO: It seems totally fair that the transform has a case-sensitive constant value for
        // to delineate the host header.  However, as the transform is, it will ONLY match, AFAIK,
        // the value that matches the exact case sensitivity of "Host", and as per the HTTP Spec,
        // header names are case INSENSITIVE!
        log.error("TODO: Fix case-sensitive check on this transform!");
        headersSpecJson.put("Host", targetClusterHostname);
        return jsonHostSwitchTemplateJson;
    }

    public JsonTransformBuilder addHostSwitchOperation(String hostname) {
         return addOperationObject(getHostSwitchOperation(hostname));
    }

    public JsonTransformBuilder addCannedOperation(String resourceName) {
        return addOperationObject(parseSpecOperationFromResource(resourceName));
    }

    private JsonTransformBuilder addOperationObject(Map<String, Object> stringObjectMap) {
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
