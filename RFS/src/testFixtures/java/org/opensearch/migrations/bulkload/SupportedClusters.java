package org.opensearch.migrations.bulkload;

import java.util.List;

import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer.ContainerVersion;

import lombok.experimental.UtilityClass;

/**
 * Defines all supported clusters
 */
@UtilityClass
public class SupportedClusters {
    public static List<ContainerVersion> sources() {
        return List.of(
            SearchClusterContainer.ES_V5_6_16,
            SearchClusterContainer.ES_V6_8_23,
            SearchClusterContainer.ES_V7_10_2,
            SearchClusterContainer.ES_V7_17,
            SearchClusterContainer.OS_V1_3_16
        );
    }

    public static List<ContainerVersion> targets() {
        return List.of(
            SearchClusterContainer.OS_V1_3_16,
            SearchClusterContainer.OS_V2_19_1
        );
    }

    public static class MigrationPair {
        private final ContainerVersion source;
        private final ContainerVersion target;

        public MigrationPair(ContainerVersion source, ContainerVersion target) {
            this.source = source;
            this.target = target;
        }

        public ContainerVersion source() { return source; }
        public ContainerVersion target() { return target; }

    }
    public static List<MigrationPair> supportedPairs(boolean includeRFSOnly) {
        var matrix = new java.util.ArrayList<>(sources().stream()
                .flatMap(source -> targets().stream()
                        .map(target -> new MigrationPair(source, target)))
                .toList());

        // Individual Pairs
        matrix.add(new MigrationPair(SearchClusterContainer.OS_V2_19_1, SearchClusterContainer.OS_V2_19_1));

        if (includeRFSOnly) {
            matrix.add(new MigrationPair(SearchClusterContainer.ES_V5_6_16, SearchClusterContainer.ES_V5_6_16));
            matrix.add(new MigrationPair(SearchClusterContainer.ES_V6_8_23, SearchClusterContainer.ES_V6_8_23));
            matrix.add(new MigrationPair(SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.ES_V7_10_2));
        }

        return matrix;
    }
}
