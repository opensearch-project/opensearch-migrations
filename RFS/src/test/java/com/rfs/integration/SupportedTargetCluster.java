package com.rfs.integration;

import java.util.stream.Stream;

import com.rfs.framework.SearchClusterContainer;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

/**
 * Defines all supported target clusters
 */
public class SupportedTargetCluster implements ArgumentsProvider {

    @Override
    public Stream<? extends Arguments> provideArguments(final ExtensionContext context) {
        return Stream.of(
                Arguments.of(SearchClusterContainer.OS_V1_3_16),
                Arguments.of(SearchClusterContainer.OS_V2_14_0)
        );
    }
}