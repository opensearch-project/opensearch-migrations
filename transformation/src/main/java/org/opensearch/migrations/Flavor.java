package org.opensearch.migrations;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum Flavor {
    ELASTICSEARCH("ES"),
    OPENSEARCH("OS"),
    AMAZON_MANAGED_OPENSEARCH("AOS"),
    AMAZON_SERVERLESS_OPENSEARCH("AOSS");

    final String shorthand;

    public boolean isOpenSearch() {
        switch (this) {
            case OPENSEARCH:
            case AMAZON_MANAGED_OPENSEARCH:
            case AMAZON_SERVERLESS_OPENSEARCH:
                return true;
            default:
                return false;
        }
    }
}
