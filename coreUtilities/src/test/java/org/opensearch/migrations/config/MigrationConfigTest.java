package org.opensearch.migrations.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class MigrationConfigTest {

    @Test
    void testLoad(@TempDir Path dir) throws IOException {
        var file = dir.resolve("testFile.yml");

        var contents = "source_cluster:\r\n" + //
            "    endpoint: \"https://capture-proxy-es:9200\"\r\n" + //
            "    allow_insecure: true\r\n" + //
            "    no_auth:\r\n" + //
            "target_cluster:\r\n" + //
            "    endpoint: \"https://opensearchtarget:9200\"\r\n" + //
            "    allow_insecure: true\r\n" + //
            "    basic_auth:\r\n" + //
            "        username: \"admin\"\r\n" + //
            "        password: \"myStrongPassword123!\"\r\n" + //
            "metrics_source:\r\n" + //
            "    prometheus:\r\n" + //
            "        endpoint: \"http://prometheus:9090\"\r\n" + //
            "backfill:\r\n" + //
            "    reindex_from_snapshot:\r\n" + //
            "        snapshot_repo: \"abc\"\r\n" + //
            "        snapshot_name: \"def\"\r\n" + //
            "        scale: 3\r\n" + //
            "        ecs:\r\n" + //
            "            cluster_name: migration-aws-integ-ecs-cluster\r\n" + //
            "            service_name: migration-aws-integ-reindex-from-snapshot\r\n" + //
            "            aws_region: us-east-1\r\n" + //
            "replay:\r\n" + //
            "  ecs:\r\n" + //
            "    cluster_name: \"migrations-dev-cluster\"\r\n" + //
            "    service_name: \"migrations-dev-replayer-service\"\r\n" + //
            "snapshot:\r\n" + //
            "  snapshot_name: \"snapshot_2023_01_01\"\r\n" + //
            "  s3:\r\n" + //
            "      repo_uri: \"s3://my-snapshot-bucket\"\r\n" + //
            "      aws_region: \"us-east-2\"\r\n" + //
            "metadata_migration:\r\n" + //
            "  min_replicas: 0\r\n" + //
            "kafka:\r\n" + //
            "  broker_endpoints: \"kafka:9092\"\r\n" + //
            "  standard:\r\n" + //
            "client_options:\r\n" + //
            "  user_agent_extra: \"test-user-agent-v1.0\"";

        Files.writeString(file, contents);

        var config = MigrationConfig.loadFrom(file.toString());

        System.out.println(config);
    }
}
