package org.opensearch.migrations.bulkload;

import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;

/**
 * Defines all supported target clusters
 */
public class SupportedTargetCluster implements ArgumentsProvider {

    @Override
    public Stream<? extends Arguments> provideArguments(final ExtensionContext context) {
        return Stream.of(Arguments.of(SearchClusterContainer.OS_V2_14_0));
    }
}
