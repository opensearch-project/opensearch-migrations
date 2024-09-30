package org.opensearch.migrations;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum Flavor {
    ELASTICSEARCH("ES"),
    OPENSEARCH("OS");

    final String shorthand;
}
