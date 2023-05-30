package org.opensearch.migrations.transform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class JoltJsonTransformBuilder {

    private static String getSubstitutionTemplate(int i) {
        return "%%SUBSTITION_" + (i+1) + "%%";
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
        ADD_ADMIN_AUTH("addAdminAuth", 1),
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
    static final TypeReference<LinkedHashMap<String, Object>> TYPE_REFERENCE_FOR_MAP_TYPE = new TypeReference<>() {};

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

    @SneakyThrows
    private Map<String, Object> parseSpecOperationFromResource(String resource) {
        return loadResourceAsJson("/jolt/operations/" + resource + ".jolt");
    }

    @SneakyThrows
    private Map<String, Object> parseSpecOperationFromResourceTemplate(String resource) {
        return loadResourceAsJson("/jolt/operations/" + resource + ".jolt.template");
    }

    private Object walkAndReplaceAll(Object o, HashMap<String, String> substitutions) {

        if (o instanceof Map.Entry) {
            var kvp = (Map.Entry<String,Object>) o;
            var keyReplacement = substitutions.get(kvp.getKey());
            if (keyReplacement != null) {
                return new AbstractMap.SimpleEntry(keyReplacement, walkAndReplaceAll(kvp.getValue(), substitutions));
            } else {
                kvp.setValue(walkAndReplaceAll(kvp.getValue(), substitutions));
                return kvp;
            }
        } else if (o instanceof Map) {
            Map replacement = null;
            for (var kvp : ((Map<String,Object>) o).entrySet()) {
                var newKvp = (Map.Entry<String, Object>) walkAndReplaceAll(kvp, substitutions);
                if (kvp != newKvp) {
                    if (replacement == null) {
                        // need to copy the map over
                        replacement = new LinkedHashMap();
                        for (var previouslySeenKvp : ((Map<String,Object>) o).entrySet()) {
                            if (previouslySeenKvp.getKey().equals(kvp.getKey())) {
                                break;
                            } else {
                                replacement.put(previouslySeenKvp.getKey(), previouslySeenKvp.getValue());
                            }
                        }
                    }
                }
                if (replacement != null) {
                    replacement.put(newKvp.getKey(), newKvp.getValue());
                }
            }
            return replacement == null ? o : replacement;
        } else if (o instanceof String) {
            var replacement = substitutions.get(o);
            return replacement == null ? o : replacement;
        } else if (o.getClass().isArray()) {
            var arr = (Object[]) o;
            for (int i = 0; i < arr.length; ++i) {
                arr[i] = walkAndReplaceAll(arr[i], substitutions);
            }
            return arr;
        } else {
            throw new RuntimeException("Unexpected node type " + o.getClass());
        }
    }

    private Map<String, Object> getOperationWithSubstitutions(OPERATION operation, String...substitutions) {
        assert substitutions.length == operation.numberOfTemplateSubstitutions;
        var joltTransformTemplate = parseSpecOperationFromResourceTemplate(operation.value);
        var replacementMap = new HashMap<String, String>();
        for (int i=0; i<substitutions.length; ++i) {
            replacementMap.put(getSubstitutionTemplate(i), substitutions[i]);
        }
        return (Map<String, Object>) walkAndReplaceAll(joltTransformTemplate, replacementMap);
    }

    public JoltJsonTransformBuilder addHostSwitchOperation(String hostname) {
        return addOperationObject(getOperationWithSubstitutions(OPERATION.HOST_SWITCH, hostname));
    }

    public JoltJsonTransformBuilder addAuthorizationOperation(String value) {
        return addOperationObject(getOperationWithSubstitutions(OPERATION.ADD_ADMIN_AUTH, value));
    }

    public JoltJsonTransformBuilder addCannedOperation(CANNED_OPERATION operation) {
        return addOperationObject(parseSpecOperationFromResource(operation.joltOperationTransformName));
    }

    public JoltJsonTransformBuilder addOperationObject(Map<String, Object> stringObjectMap) {
        chainedSpec.add(stringObjectMap);
        return this;
    }

    public JsonTransformer build() {
        if (chainedSpec.size() == 0) {
            addCannedOperation(CANNED_OPERATION.PASS_THRU);
        }
        return new JoltJsonTransformer((List) chainedSpec);
    }
}
