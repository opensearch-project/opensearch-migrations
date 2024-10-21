package org.opensearch.migrations.transform;

import lombok.NonNull;

public interface IJsonPreconditionProvider {
    /**
     * Create a new precondition from the given configuration.  This precondition
     * will be used repeatedly and concurrently from different threads against
     * messages.
     * @param jsonConfig is a List, Map, String, or null that should be used to configure the
     *                   IJsonPrecondition that is being created
     * @return
     */
    IJsonPrecondition createPrecondition(Object jsonConfig);

    /**
     * Friendly name that can be used as a key to identify precondition providers.
     * @return
     */
    default @NonNull String getName() {
        return this.getClass().getSimpleName();
    }
}
