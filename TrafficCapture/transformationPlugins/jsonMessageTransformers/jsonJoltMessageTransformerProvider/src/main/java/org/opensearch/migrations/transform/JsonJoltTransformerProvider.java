package org.opensearch.migrations.transform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class JsonJoltTransformerProvider implements IJsonTransformerProvider {

    public static final String SCRIPT_KEY = "script";
    public static final String CANNED_KEY = "canned";

    @Override
    public IJsonTransformer createTransformer(Object jsonConfig) {
        var builder = JsonJoltTransformer.newBuilder();
        var configs = new ArrayList<Map<String, Object>>();
        try {
            if (jsonConfig instanceof Map) {
                configs.add((Map<String, Object>) jsonConfig);
            } else if (jsonConfig instanceof List) {
                for (var c : (List) jsonConfig) {
                    configs.add((Map<String, Object>) c);
                }
            } else {
                throw new IllegalArgumentException(getConfigUsageStr());
            }
            for (var c : configs) {
                if (c.size() != 1) {
                    throw new IllegalArgumentException(getConfigUsageStr());
                }
                var cannedValue = c.get(CANNED_KEY);
                var scriptValue = c.get(SCRIPT_KEY);
                if (cannedValue != null) {
                    var cannedValueStr = (String) cannedValue;
                    var cannedOperation = getCannedOperationOrThrow(cannedValueStr);
                    builder.addCannedOperation(cannedOperation);
                } else if (scriptValue != null) {
                    builder.addOperationObject((Map<String, Object>) scriptValue);
                } else {
                    throw new IllegalArgumentException(getConfigUsageStr());
                }
            }
        } catch (ClassCastException e) {
            throw new IllegalArgumentException(getConfigUsageStr(), e);
        }
        return builder.build();
    }

    private JsonJoltTransformBuilder.CANNED_OPERATION getCannedOperationOrThrow(String cannedValueStr) {
        try {
            return JsonJoltTransformBuilder.CANNED_OPERATION.valueOf(cannedValueStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(getConfigUsageStr(), e);
        }
    }

    private String getConfigUsageStr() {
        return this.getClass().getName()
            + " expects the incoming configuration "
            + "to be a Map<String,Object> or a List<Map<String,Object>>.  "
            + "Each of the Maps should have one key-value, either \"canned\" or \"script\".  "
            + "Canned values should be a string that specifies the name of the pre-built transformation to use "
            + Arrays.stream(JsonJoltTransformBuilder.CANNED_OPERATION.values())
                .map(e -> e.toString())
                .collect(Collectors.joining(","))
            + ".  "
            + "Script values should be a fully-formed inlined Jolt transformation in json form.  "
            + "All of the values (canned or inlined) within a configuration will be concatenated "
            + "into one chained Jolt transformation.";
    }
}
