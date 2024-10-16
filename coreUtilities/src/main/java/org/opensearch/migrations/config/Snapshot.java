package org.opensearch.migrations.config;

public class Snapshot {
    public String snapshot_name;
    public S3Bucket s3;
    public FileSystem fs;
}
