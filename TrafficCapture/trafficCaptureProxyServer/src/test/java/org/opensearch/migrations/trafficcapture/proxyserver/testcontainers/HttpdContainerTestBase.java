package org.opensearch.migrations.trafficcapture.proxyserver.testcontainers;

import org.opensearch.migrations.testutils.SharedDockerImageNames;

import org.testcontainers.containers.GenericContainer;

public class HttpdContainerTestBase extends TestContainerTestBase<GenericContainer<?>> {

    private static final GenericContainer<?> httpd = new GenericContainer(SharedDockerImageNames.HTTPD)
        .withExposedPorts(80); // Container Port

    public GenericContainer<?> getContainer() {
        return httpd;
    }
}
