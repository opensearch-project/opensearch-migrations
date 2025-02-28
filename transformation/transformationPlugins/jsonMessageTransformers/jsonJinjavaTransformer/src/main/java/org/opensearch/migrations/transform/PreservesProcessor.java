package org.opensearch.migrations.transform;

import java.util.List;
import java.util.Map;

public class PreservesProcessor {
    private static final String PRESERVE_KEY = "preserve";
    private static final String PRESERVE_WHEN_MISSING_KEY = "preserveWhenMissing";

    private PreservesProcessor() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> doFinalSubstitutions(Map<String, Object> incomingJson, Map<String, Object> parsedObj) {
        processPreserves(incomingJson, parsedObj);

        processPreserves(
            (Map<String, Object>) incomingJson.get(JsonKeysForHttpMessage.PAYLOAD_KEY),
            (Map<String, Object>) parsedObj.get(JsonKeysForHttpMessage.PAYLOAD_KEY)
        );

        return parsedObj;
    }

    private static void processPreserves(Map<String, Object> source, Map<String, Object> target) {
        if (target == null || source == null) {
            return;
        }

        copyValues(source, target, PRESERVE_KEY, true);
        copyValues(source, target, PRESERVE_WHEN_MISSING_KEY, false);
    }

    private static void copyValues(Map<String, Object> source, Map<String, Object> target,
                                   String directiveKey, boolean forced) {
        Object directive = target.remove(directiveKey);
        if (directive == null) {
            return;
        }

        if (directive.equals("*")) {
            source.forEach((key, value) -> {
                if (forced || !target.containsKey(key)) {
                    target.put(key, value);
                }
            });
        } else if (directive instanceof List) {
            ((List<String>) directive).forEach(key -> {
                if (source.containsKey(key) &&
                    (forced || !target.containsKey(key)))
                {
                    target.put(key, source.get(key));
                }
            });
        }
    }
}
