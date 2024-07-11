package org.opensearch.migrations.metadata;

import java.util.List;

public class EvaluationResponse {

    public Clusters clusters;
    public Candidates candidates;
    public Transformations transformations;
    public Result result;
    public List<String> issues;

}
