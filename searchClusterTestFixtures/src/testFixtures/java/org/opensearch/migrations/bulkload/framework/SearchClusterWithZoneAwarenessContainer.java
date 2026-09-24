package org.opensearch.migrations.bulkload.framework;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

    private static Map<String, String> getMultipleAwarenessValueConfig(List<Integer> valuesPerAttributeCount) {
        if (valuesPerAttributeCount.size() > 3) {
            throw new IllegalArgumentException("This class only supports up to 3 attributes");
        }

        List<String> attributeNames = List.of("zone", "rack", "arbitrary");

        Map<String, String> values = new HashMap<>();
        values.put("cluster.routing.allocation.awareness.attributes",
            String.join(", ", attributeNames.subList(0, valuesPerAttributeCount.size())));
        values.put("cluster.routing.allocation.awareness.balance", "true");

        values.putAll(IntStream.range(0, valuesPerAttributeCount.size())
            .boxed()
            .collect(Collectors.toMap(
                i -> "cluster.routing.allocation.awareness.force." + attributeNames.get(i) + ".values",
                i -> IntStream.range(0, valuesPerAttributeCount.get(i))
                    .mapToObj(j -> "zone" + j)
                    .collect(Collectors.joining(","))
            )));

        return values;
    }


    public SearchClusterWithZoneAwarenessContainer(List<Integer> valuesPerAttributeCount) {
        super(version,
                getMultipleAwarenessValueConfig(valuesPerAttributeCount)
        );
    }
}
