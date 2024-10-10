package org.opensearch.migrations.data;

import java.util.Arrays;
import java.util.List;

import org.opensearch.migrations.data.workloads.Workloads;

import com.beust.jcommander.Parameter;

public class WorkloadOptions {
    @Parameter(names = { "--workloads", "-w" }, description = "The list of workloads to run, defaults to all available workloads.", required = false)
    public List<Workloads> workloads = Arrays.asList(Workloads.values());

    @Parameter(names = { "--docs-per-workload" }, description = "The number of documents per each workload")
    public int totalDocs = 1000;

    @Parameter(names = { "--max-bulk-request-batch-size "}, description = "For bulk requests the larger batch size")
    public int maxBulkBatchSize = 50;

    public IndexOptions index = new IndexOptions();
}
