package org.opensearch.migrations.config;

public class ReindexFromSnapshot {
    public Object docker;
    public ElasticContainerService ecs;
    public String snapshot_name;
    public String snapshot_repo;
    public String local_dir;
    public int scale;
}
