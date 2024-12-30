package org.opensearch.migrations;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;

public class FlavorTest {
    @Test
    void testIsOpenSearch() {
        var trueCases = List.of(Flavor.AMAZON_MANAGED_OPENSEARCH, Flavor.AMAZON_SERVERLESS_OPENSEARCH, Flavor.OPENSEARCH);
        for (var testCase : trueCases) {
            assertThat(testCase.isOpenSearch(), equalTo(true));
        }

        var falseCases = List.of(Flavor.ELASTICSEARCH);
        for (var testCase : falseCases) {
            assertThat(testCase.isOpenSearch(), equalTo(false));
        }
    }

    @Test
    void uniqueValues() {
        // Make sure values in the flavor enum are never marked as equal to one another
        var valuesInSet = new HashSet<>(Arrays.asList(Flavor.values()));
        assertThat(valuesInSet, hasSize(Flavor.values().length));
    }
}
