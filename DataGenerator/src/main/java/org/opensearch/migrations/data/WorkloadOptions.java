package org.opensearch.migrations.data;

import java.util.Arrays;
import java.util.List;

import org.opensearch.migrations.data.workloads.Workloads;

import com.beust.jcommander.Parameter;

public class WorkloadOptions {
    @Parameter(names = { "--workloads", "-w" }, description = "The list of workloads to run, defaults to all available workloads.", required = false)
    public List<Workloads> workloads = Arrays.asList(Workloads.values());

    @Parameter(names = { "--docs-per-workload-count" }, description = "The number of documents per workload")
    public int totalDocs = 1000;

    @Parameter(names = { "--max-bulk-request-batch-count" }, description = "The maximum batch count for bulk requests")
    public int maxBulkBatchSize = 50;

    public final IndexOptions index = new IndexOptions();
}
