package org.opensearch.migrations.transformation;

import java.util.Map;

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
            @SuppressWarnings("unchecked")
            var incomingJsonMap = ((Map<String, Object>) incomingJson);
            if (incomingJsonMap.containsKey("index")) {
                ((Map<String, Object>) ((Map<String, Object>) incomingJson).get("index")).remove("_type");
            }
            return incomingJson;
        }
    }
}
