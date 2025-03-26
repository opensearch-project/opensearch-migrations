package org.opensearch.migrations.trafficcapture.proxyserver.testcontainers;

import org.opensearch.migrations.testutils.SharedDockerImageNames;

import org.testcontainers.kafka.ConfluentKafkaContainer;


public class KafkaContainerTestBase extends TestContainerTestBase<ConfluentKafkaContainer> {

    private static final ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(SharedDockerImageNames.KAFKA);

    public ConfluentKafkaContainer getContainer() {
        return kafka;
    }
}
