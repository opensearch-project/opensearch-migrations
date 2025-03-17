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

        // equals, hashCode, and toString methods
    }
    public static List<MigrationPair> supportedPairs(boolean supports_6_8_target) {
        // Most of our source/target pairs can be determined by crossing the arrays, but there are a few special cases
        // that don't fit into that framework because a given source or target is only compatible with specific partners.
        // In particular, ES 6.8 can be a target _only_ for migrations from ES 6.8, and OS 2.x can be a source _only_
        // for migrations to OS 2.x (i.e. no downgrades).
        var matrix = new java.util.ArrayList<>(sources().stream()
                .flatMap(source -> targets().stream()
                        .map(target -> new MigrationPair(source, target)))
                .toList());
        matrix.add(new MigrationPair(SearchClusterContainer.OS_V2_19_1, SearchClusterContainer.OS_V2_19_1));
        if (supports_6_8_target) {
            matrix.add(new MigrationPair(SearchClusterContainer.ES_V6_8_23, SearchClusterContainer.OS_V2_19_1));
        }
        return matrix;
    }
}
