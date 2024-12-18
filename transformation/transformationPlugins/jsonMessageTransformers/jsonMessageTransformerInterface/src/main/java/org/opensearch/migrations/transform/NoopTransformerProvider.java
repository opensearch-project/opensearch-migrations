package org.opensearch.migrations.transform;

public class NoopTransformerProvider implements IJsonTransformerProvider {
    private static class NoopTransformer implements IJsonTransformer {
        @Override
        public Object transformJson(Object incomingJson) {
            return incomingJson;
        }
    }

    @Override
    public IJsonTransformer createTransformer(Object jsonConfig) {
        return new NoopTransformer();
    }
}
