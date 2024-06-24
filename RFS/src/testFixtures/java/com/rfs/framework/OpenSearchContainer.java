package com.rfs.framework;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import lombok.extern.slf4j.Slf4j;

/**
 * Containerized version of OpenSearch cluster
 */
@Slf4j
public class OpenSearchContainer extends ElasticsearchContainer {
    public OpenSearchContainer(final Version version) {
        super(version);
    }
}
