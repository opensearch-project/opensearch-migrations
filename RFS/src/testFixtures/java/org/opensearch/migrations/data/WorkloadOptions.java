package org.opensearch.migrations.data;

import java.util.List;

import org.opensearch.migrations.data.workloads.Geonames;
import org.opensearch.migrations.data.workloads.HttpLogs;
import org.opensearch.migrations.data.workloads.Nested;
import org.opensearch.migrations.data.workloads.NycTaxis;
import org.opensearch.migrations.data.workloads.Workload;

public class WorkloadOptions {
    public List<Workload> workloads = List.of(
        new HttpLogs(),
        new Geonames(),
        new NycTaxis(),
        new Nested()
    );

    public IndexOptions index = new IndexOptions();

    public int totalDocs = 1000;

    public int maxBulkBatchSize = 50;
}
