package org.opensearch.migrations.transform;

import lombok.NonNull;

public interface IJsonTransformerProvider {
    /**
     * Create a new transformer from the given configuration.  This transformer
     * will be used repeatedly and concurrently from different threads to modify
     * messages.
     *
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

    /**
     * Materialization type for a file-backed provider config key.
     *
     * <p>The workflow and user schema only identify files. Providers own the
     * meaning of each config key and can ask the loader to materialize a file
     * as JSON, text, bytes, base64 text, or a runtime path string before
     * createTransformer is called.
     *
     * @param configKey provider config key, or the file name for directory-loaded values
     * @return expected materialized value type
     */
    default @NonNull ConfigFileValueType getFileBackedConfigValueType(@NonNull String configKey) {
        return ConfigFileValueType.TEXT;
    }
}
