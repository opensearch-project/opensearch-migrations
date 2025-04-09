package org.opensearch.migrations;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
@Builder
@EqualsAndHashCode
public class AwarenessAttributeSettings {
    private final boolean balanceEnabled;
    private final int numberOfAttributeValues;
}
