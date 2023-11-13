package org.opensearch.migrations.transform;

public interface IJsonTransformerProvider {
    /**
     * Create a new transformer from the given configuration.  This transformer
     * will be used repeatedly and concurrently from different threads to modify
     * messages.
     * @param jsonConfig is a List, Map, String, or null that should be used to configure the
     *                   IJsonTransformer that is being created
     * @return
     */
    IJsonTransformer createTransformer(Object jsonConfig);
}
