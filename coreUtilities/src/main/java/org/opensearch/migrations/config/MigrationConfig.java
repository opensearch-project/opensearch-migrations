package org.opensearch.migrations.config;

import java.io.FileInputStream;
import java.io.IOException;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

public class MigrationConfig {
    public Cluster source_cluster;
    public Cluster target_cluster;
    public Object metrics_source;
    public Backfill backfill;
    public Replay replay;
    public Snapshot snapshot;
    public SnapshotCreator snapshotCreator;
    public MetadataMigration metadata_migration;
    public Kafka kafka;
    public Object client_options;

    public static MigrationConfig loadFrom(String path) throws IOException {
        var yaml = new Yaml(new Constructor(MigrationConfig.class, new LoaderOptions()));
        try (var inputStream = new FileInputStream(path)) {
            return yaml.load(inputStream);
        }
    }
}
