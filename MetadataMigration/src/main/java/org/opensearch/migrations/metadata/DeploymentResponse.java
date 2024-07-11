package org.opensearch.migrations.metadata;

import java.util.List;

public class DeploymentResponse {
    public Clusters clusters;
    public Candidates deployed;
    public Transformations transformations;
    public Result result;
    public List<String> issues;
}
