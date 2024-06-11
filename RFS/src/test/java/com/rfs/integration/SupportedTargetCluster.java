package com.rfs.integration;

import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

import com.rfs.framework.OpenSearchContainer;

/**
 * Defines all supported target clusters
 */
public class SupportedTargetCluster implements ArgumentsProvider {

    @Override
    public Stream<? extends Arguments> provideArguments(final ExtensionContext context) {
        return Stream.of(
                Arguments.of(OpenSearchContainer.Version.V1_3_15), 
                Arguments.of(OpenSearchContainer.Version.V2_14_0)
        );
    }
}