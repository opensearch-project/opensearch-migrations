package org.opensearch.migrations.transform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JsonJoltTransformBuilder {

    private static String getSubstitutionTemplate(int i) {
        return "%%SUBSTITION_" + (i + 1) + "%%";
    }

    public enum CANNED_OPERATION {
        ADD_GZIP("addGzip"),
        MAKE_CHUNKED("makeChunked"),
        PASS_THRU("passThru");

        private final String joltOperationTransformName;

        CANNED_OPERATION(String s) {
            joltOperationTransformName = s;
        }

        @Override
        public String toString() {
            return joltOperationTransformName;
        }
    }

    private enum OPERATION {
        ADD_GZIP(CANNED_OPERATION.ADD_GZIP.joltOperationTransformName),
        MAKE_CHUNKED(CANNED_OPERATION.MAKE_CHUNKED.joltOperationTransformName),
        PASS_THRU(CANNED_OPERATION.PASS_THRU.joltOperationTransformName),
        HOST_SWITCH("hostSwitch", 1);

        private final String value;
        private int numberOfTemplateSubstitutions;

        OPERATION(String s, int numberOfTemplateSubstitutions) {
            this.value = s;
            this.numberOfTemplateSubstitutions = numberOfTemplateSubstitutions;
        }

        OPERATION(String s) {
            this(s, 0);
        }

        @Override
        public String toString() {
            return "(" + value + "," + numberOfTemplateSubstitutions + ")";
        }
    }

    /**
     * Visibility increased for testing
     */
    static final TypeReference<LinkedHashMap<String, Object>> TYPE_REFERENCE_FOR_MAP_TYPE = new TypeReference<>() {
    };

    ObjectMapper mapper = new ObjectMapper();
    List<Map<String, Object>> chainedSpec = new ArrayList<>();

    public Map<String, Object> loadResourceAsJson(String path) throws IOException {
        return loadResourceAsJson(mapper, path);
    }

    public static Map<String, Object> loadResourceAsJson(ObjectMapper mapper, String path) throws IOException {
        try (InputStream inputStream = JsonJoltTransformBuilder.class.getResourceAsStream(path)) {
            return mapper.readValue(inputStream, TYPE_REFERENCE_FOR_MAP_TYPE);
        }
    }

    @SneakyThrows
    private Map<String, Object> parseSpecOperationFromResource(String resource) {
        return loadResourceAsJson("/jolt/operations/" + resource + ".jolt");
    }

    @SneakyThrows
    private Map<String, Object> getOperationWithSubstitutions(OPERATION operation, String... substitutions) {
        var path = "/jolt/operations/" + operation.value + ".jolt.template";
        assert substitutions.length == operation.numberOfTemplateSubstitutions;
        try (InputStream inputStream = JsonJoltTransformBuilder.class.getResourceAsStream(path)) {
            var contentBytes = inputStream.readAllBytes();
            var contentsStr = new String(contentBytes, StandardCharsets.UTF_8);
            for (int i = 0; i < substitutions.length; ++i) {
                contentsStr = contentsStr.replaceAll(getSubstitutionTemplate(i), substitutions[i]);
            }
            return mapper.readValue(contentsStr, TYPE_REFERENCE_FOR_MAP_TYPE);
        }
    }

    public JsonJoltTransformBuilder addHostSwitchOperation(String hostname) {
        return addOperationObject(getOperationWithSubstitutions(OPERATION.HOST_SWITCH, hostname));
    }

    public JsonJoltTransformBuilder addCannedOperation(CANNED_OPERATION operation) {
        return addOperationObject(parseSpecOperationFromResource(operation.joltOperationTransformName));
    }

    public JsonJoltTransformBuilder addOperationObject(Map<String, Object> stringObjectMap) {
        chainedSpec.add(stringObjectMap);
        return this;
    }

    public IJsonTransformer build() {
        if (chainedSpec.isEmpty()) {
            addCannedOperation(CANNED_OPERATION.PASS_THRU);
        }
        return new JsonJoltTransformer((List) chainedSpec);
    }
}
