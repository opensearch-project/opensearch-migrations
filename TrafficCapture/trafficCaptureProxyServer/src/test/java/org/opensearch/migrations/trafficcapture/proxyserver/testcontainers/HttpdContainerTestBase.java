package org.opensearch.migrations.trafficcapture.proxyserver.testcontainers;

import org.testcontainers.containers.GenericContainer;

public class HttpdContainerTestBase extends TestContainerTestBase<GenericContainer<?>> {

    private static final GenericContainer<?> httpd = new GenericContainer("httpd:alpine").withExposedPorts(80); // Container
                                                                                                                // Port

    public GenericContainer<?> getContainer() {
        return httpd;
    }
}
