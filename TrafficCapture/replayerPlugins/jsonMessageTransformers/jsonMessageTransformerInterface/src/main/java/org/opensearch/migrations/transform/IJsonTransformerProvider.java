package org.opensearch.migrations.transform;

import java.util.Optional;

public interface IJsonTransformerProvider {
    IJsonTransformer createTransformer(Optional<String> config);
}
