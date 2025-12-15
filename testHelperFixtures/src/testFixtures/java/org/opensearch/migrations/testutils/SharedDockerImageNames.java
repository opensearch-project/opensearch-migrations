package org.opensearch.migrations.testutils;

import org.testcontainers.utility.DockerImageName;

public interface SharedDockerImageNames {
    DockerImageName KAFKA = DockerImageName.parse("apache/kafka:3.8.0");
    DockerImageName HTTPD = DockerImageName.parse("httpd:2.4-alpine");

}
