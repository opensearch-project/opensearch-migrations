package org.opensearch.migrations.trafficcapture.proxyserver.testcontainers;

import org.testcontainers.containers.GenericContainer;

abstract class TestContainerTestBase<T extends GenericContainer<?>> {

    public void start() {
        getContainer().start();
    }

    public void stop() {
        getContainer().start();
    }

    abstract T getContainer();
}
