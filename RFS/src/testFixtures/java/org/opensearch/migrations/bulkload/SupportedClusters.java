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
            SearchClusterContainer.OS_V2_14_0
        );
    }
}
