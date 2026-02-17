package org.opensearch.migrations.bulkload;

import java.util.List;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.framework.SearchClusterContainer;
import org.opensearch.migrations.bulkload.framework.SearchClusterContainer.ContainerVersion;

import lombok.experimental.UtilityClass;

/**
 * Defines all supported clusters.
 *
 * The 'sources()' list reflects officially supported source versions published in documentation.
 * The 'extendedSources()' list includes additional versions that are known to work reliably
 * without requiring --allow-loose-version-matching, but are not publicly advertised.
 */
@UtilityClass
public class SupportedClusters {
    private static List<ContainerVersion> sources() {
        return List.of(
            SearchClusterContainer.OS_V1_3_20,
            SearchClusterContainer.ES_V8_19,
            SearchClusterContainer.ES_V7_17,
            SearchClusterContainer.ES_V7_10_2,
            SearchClusterContainer.ES_V6_8_23,
            SearchClusterContainer.ES_V5_6_16,
            SearchClusterContainer.ES_V2_4_6,
            SearchClusterContainer.ES_V1_7_6
        );
    }

    public static List<ContainerVersion> extendedSources() {
        return List.of(
            // OpenSearch 1.x
            SearchClusterContainer.OS_V1_0_1,
            SearchClusterContainer.OS_V1_1_0,
            SearchClusterContainer.OS_V1_2_4,
            SearchClusterContainer.OS_V1_3_20,
            // OpenSearch 2.x
            SearchClusterContainer.OS_V2_0_1,
            SearchClusterContainer.OS_V2_1_0,
            SearchClusterContainer.OS_V2_2_1,
            SearchClusterContainer.OS_V2_3_0,
            SearchClusterContainer.OS_V2_4_1,
            SearchClusterContainer.OS_V2_5_0,
            SearchClusterContainer.OS_V2_6_0,
            SearchClusterContainer.OS_V2_7_0,
            SearchClusterContainer.OS_V2_8_0,
            SearchClusterContainer.OS_V2_9_0,
            SearchClusterContainer.OS_V2_10_0,
            SearchClusterContainer.OS_V2_11_1,
            SearchClusterContainer.OS_V2_12_0,
            SearchClusterContainer.OS_V2_13_0,
            SearchClusterContainer.OS_V2_14_0,
            SearchClusterContainer.OS_V2_15_0,
            SearchClusterContainer.OS_V2_16_0,
            SearchClusterContainer.OS_V2_17_1,
            SearchClusterContainer.OS_V2_18_0,
            SearchClusterContainer.OS_V2_19_4,
            // Elasticsearch
            SearchClusterContainer.ES_V8_18,
            SearchClusterContainer.ES_V8_17,
            SearchClusterContainer.ES_V8_16,
            SearchClusterContainer.ES_V8_15,
            SearchClusterContainer.ES_V8_14,
            SearchClusterContainer.ES_V8_13,
            SearchClusterContainer.ES_V8_12,
            SearchClusterContainer.ES_V8_11,
            SearchClusterContainer.ES_V8_10,
            SearchClusterContainer.ES_V8_9,
            SearchClusterContainer.ES_V8_8,
            SearchClusterContainer.ES_V8_7,
            SearchClusterContainer.ES_V8_6,
            SearchClusterContainer.ES_V8_5,
            SearchClusterContainer.ES_V8_4,
            SearchClusterContainer.ES_V8_3,
            SearchClusterContainer.ES_V8_2,
            SearchClusterContainer.ES_V8_1,
            SearchClusterContainer.ES_V8_0,
            SearchClusterContainer.ES_V7_16,
            SearchClusterContainer.ES_V7_15,
            SearchClusterContainer.ES_V7_14,
            SearchClusterContainer.ES_V7_13,
            SearchClusterContainer.ES_V7_12,
            SearchClusterContainer.ES_V7_11,
            SearchClusterContainer.ES_V7_9,
            SearchClusterContainer.ES_V7_8,
            SearchClusterContainer.ES_V7_7,
            SearchClusterContainer.ES_V7_6,
            SearchClusterContainer.ES_V7_5,
            SearchClusterContainer.ES_V7_4,
            SearchClusterContainer.ES_V7_3,
            SearchClusterContainer.ES_V7_2,
            SearchClusterContainer.ES_V7_1,
            SearchClusterContainer.ES_V7_0,
            SearchClusterContainer.ES_V6_7,
            SearchClusterContainer.ES_V6_6,
            SearchClusterContainer.ES_V6_5,
            SearchClusterContainer.ES_V6_4,
            SearchClusterContainer.ES_V6_3,
            SearchClusterContainer.ES_V6_2,
            SearchClusterContainer.ES_V6_1,
            SearchClusterContainer.ES_V6_0,
            SearchClusterContainer.ES_V5_5,
            SearchClusterContainer.ES_V5_4,
            SearchClusterContainer.ES_V5_3,
            SearchClusterContainer.ES_V5_2,
            SearchClusterContainer.ES_V5_1,
            SearchClusterContainer.ES_V5_0,
            SearchClusterContainer.ES_V2_3,
            SearchClusterContainer.ES_V2_2,
            SearchClusterContainer.ES_V2_1,
            SearchClusterContainer.ES_V2_0,
            SearchClusterContainer.ES_V1_6,
            SearchClusterContainer.ES_V1_5
        );
    }

    public static List<ContainerVersion> targets() {
        return List.of(
            SearchClusterContainer.OS_V1_3_20,
            SearchClusterContainer.OS_V2_19_4,
            SearchClusterContainer.OS_V3_0_0
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
        matrix.add(new MigrationPair(SearchClusterContainer.OS_V2_19_4, SearchClusterContainer.OS_V2_19_4));
        matrix.add(new MigrationPair(SearchClusterContainer.ES_V7_17, SearchClusterContainer.ES_V7_10_2));

        if (includeRFSOnly) {
            matrix.add(new MigrationPair(SearchClusterContainer.ES_V5_6_16, SearchClusterContainer.ES_V5_6_16));
            matrix.add(new MigrationPair(SearchClusterContainer.ES_V6_8_23, SearchClusterContainer.ES_V6_8_23));
            matrix.add(new MigrationPair(SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.ES_V7_10_2));
        }

        return matrix;
    }

    /**
     * Small representative set of source→target pairs for smoke testing the full E2E pipeline.
     * Source-only coverage is in SnapshotReaderEndToEndTest (O(M)).
     * Target-only coverage is in TargetWriteEndToEndTest (O(N)).
     * These smoke pairs validate the wiring between reading and writing for key migration paths.
     */
    public static List<MigrationPair> smokePairs() {
        return List.of(
            // Oldest supported → latest target
            new MigrationPair(SearchClusterContainer.ES_V1_7_6, SearchClusterContainer.OS_V2_19_4),
            // Most common migration path
            new MigrationPair(SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.OS_V2_19_4),
            // OS upgrade path
            new MigrationPair(SearchClusterContainer.OS_V1_3_20, SearchClusterContainer.OS_V2_19_4),
            // Latest ES → latest OS
            new MigrationPair(SearchClusterContainer.ES_V8_19, SearchClusterContainer.OS_V2_19_4),
            // Target version coverage: OS 1.3
            new MigrationPair(SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.OS_V1_3_20),
            // Target version coverage: OS 3.0
            new MigrationPair(SearchClusterContainer.ES_V7_10_2, SearchClusterContainer.OS_V3_0_0)
        );
    }

    public static List<SearchClusterContainer.ContainerVersion> supportedSources(boolean includeRFSOnly) {
        return SupportedClusters.supportedPairs(includeRFSOnly).stream()
            .map(SupportedClusters.MigrationPair::source)
            .distinct()
            .toList();
    }

    public static List<SearchClusterContainer.ContainerVersion> supportedTargets(boolean includeRFSOnly) {
        return SupportedClusters.supportedPairs(includeRFSOnly).stream()
            .map(SupportedClusters.MigrationPair::target)
            .distinct()
            .toList();
    }

    public static List<SearchClusterContainer.ContainerVersion> supportedSourcesOrTargets(boolean includeRFSOnly) {
        return Stream.concat(supportedSources(includeRFSOnly).stream(),
                supportedTargets(includeRFSOnly).stream())
                .distinct()
                .toList();
    }

}
