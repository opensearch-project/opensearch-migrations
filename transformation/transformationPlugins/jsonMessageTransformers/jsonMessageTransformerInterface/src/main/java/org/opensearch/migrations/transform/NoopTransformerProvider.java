package org.opensearch.migrations.transform;

import java.util.Map;

public class NoopTransformerProvider implements IJsonTransformerProvider {
    private static class NoopTransformer implements IJsonTransformer {
        @Override
        public Map<String, Object> transformJson(Map<String, Object> incomingJson) {
            return incomingJson;
        }
    }

    @Override
    public IJsonTransformer createTransformer(Object jsonConfig) {
        return new NoopTransformer();
    }
}
