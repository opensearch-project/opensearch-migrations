package org.opensearch.migrations.transform;

import lombok.NonNull;

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

    /**
     * Friendly name that can be used as a key to identify transformer providers.
     * @return
     */
    default @NonNull String getName() {
        return this.getClass().getSimpleName();
    }
}
