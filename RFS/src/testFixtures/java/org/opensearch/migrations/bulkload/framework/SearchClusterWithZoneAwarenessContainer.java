package org.opensearch.migrations.bulkload.framework;

import org.opensearch.migrations.Version;

import java.util.Map;

public class SearchClusterWithZoneAwarenessContainer extends SearchClusterContainer {
    static SearchClusterContainer.ContainerVersion version = SearchClusterContainer.OS_LATEST;

    public SearchClusterWithZoneAwarenessContainer() {
        super(SearchClusterContainer.OS_LATEST);
    }

    public SearchClusterWithZoneAwarenessContainer(int availabilityZoneCount) {
        super(version,
                Map.of("cluster.routing.allocation.awareness.attributes", "zone",
                        "cluster.routing.allocation.awareness.force.zone.values",
                            String.join(",", java.util.stream.IntStream.range(0, availabilityZoneCount).mapToObj(i -> "zone" + i).toArray(String[]::new)),
                        "cluster.routing.allocation.awareness.balance", "true"
                )
        );
    }
}
