package org.opensearch.migrations;

import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.data.WorkloadOptions;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;

public class DataGeneratorArgs {
    @Parameter(names = {"--help", "-h"}, help = true, description = "Displays information about how to use this tool")
    public boolean help;

    @ParametersDelegate
    public ConnectionContext.TargetArgs targetArgs = new ConnectionContext.TargetArgs();

    @ParametersDelegate
    public WorkloadOptions workloadOptions = new WorkloadOptions();
}
