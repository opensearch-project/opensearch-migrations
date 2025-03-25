package org.opensearch.migrations.data;

import java.util.Arrays;
import java.util.List;

import org.opensearch.migrations.data.workloads.Workloads;

import com.beust.jcommander.Parameter;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WorkloadOptions {
    @Parameter(names = { "--workloads", "-w" }, description = "The list of workloads to run, defaults to all available workloads.", required = false)
    private List<Workloads> workloads = Arrays.asList(Workloads.values());

    @Parameter(names = { "--docs-per-workload-count" }, description = "The number of documents per workload")
    private int totalDocs = 1000;

    @Parameter(names = { "--max-bulk-request-batch-count" }, description = "The maximum batch count for bulk requests")
    private int maxBulkBatchSize = 50;

    private String defaultDocType = null;

    private String defaultDocRouting = null;

    private final IndexOptions index = new IndexOptions();

    private boolean refreshAfterEachWrite = false;
}
