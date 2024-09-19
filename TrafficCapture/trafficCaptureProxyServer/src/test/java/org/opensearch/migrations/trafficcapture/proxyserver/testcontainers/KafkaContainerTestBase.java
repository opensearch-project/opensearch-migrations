package org.opensearch.migrations.trafficcapture.proxyserver.testcontainers;

import org.opensearch.migrations.testutils.SharedDockerImageNames;

import org.testcontainers.containers.KafkaContainer;

public class KafkaContainerTestBase extends TestContainerTestBase<KafkaContainer> {

    private static final KafkaContainer kafka = new KafkaContainer(SharedDockerImageNames.KAFKA);

    public KafkaContainer getContainer() {
        return kafka;
    }
}
