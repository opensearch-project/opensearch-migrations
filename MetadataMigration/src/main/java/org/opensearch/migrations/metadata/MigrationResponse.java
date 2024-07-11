package org.opensearch.migrations.metadata;

import java.util.List;

public class MigrationResponse {
    public Clusters clusters;
    public Candidates migrated;
    public Transformations transformations;
    public Result result;
    public List<String> issues;
}
