package org.opensearch.migrations.transformation;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.IJsonTransformerProvider;

/**
 * This is a JsonTransformer for doc transforms to remove type. Used for ES7+ and OS
 */
public class JsonTransformerForDocumentTypeRemovalProvider implements IJsonTransformerProvider {
    @Override
    public IJsonTransformer createTransformer(Object jsonConfig) {
        return new Transformer();
    }

    private static class Transformer implements IJsonTransformer {
        @Override
        @SuppressWarnings("unchecked")
        public Object transformJson(Object incomingJson) {
            if (incomingJson instanceof Map) {
                return transformMap(incomingJson);
            } else if (incomingJson instanceof List) {
                var list = (List<Object>) incomingJson;
                return list.stream().map(this::transformMap).collect(Collectors.toList());
            } else {
                throw new IllegalArgumentException("Unsupported JSON type: " + incomingJson.getClass());
            }
        }

        @SuppressWarnings("unchecked")
        private Object transformMap(Object incomingJson) {
            var incomingMap = (Map<String, Object>) incomingJson;
            if (incomingMap.containsKey("operation")) {
                ((Map<String, Object>) incomingMap.get("operation")).remove("_type");
            }
            return incomingMap;
        }
    }
}
