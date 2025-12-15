package org.opensearch.migrations.testutils;

import org.testcontainers.utility.DockerImageName;

public interface SharedDockerImageNames {
    DockerImageName KAFKA = DockerImageName.parse("confluentinc/cp-kafka:7.5.0");
    DockerImageName HTTPD = DockerImageName.parse("httpd:2.4.66-alpine");
}
