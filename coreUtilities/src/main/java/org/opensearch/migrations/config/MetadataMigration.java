package org.opensearch.migrations.config;

import java.util.List;

public class MetadataMigration {
    public String otel_endpoint;
    public String local_dir;
    public int min_replicas;
    
    public List<String> index_allowlist;
    public List<String> index_template_allowlist;
    public List<String> component_template_allowlist;
}
