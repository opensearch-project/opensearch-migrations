package org.opensearch.migrations;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum Flavor {
    Elasticsearch("ES"),
    OpenSearch("OS");

    final String shorthand;
}
