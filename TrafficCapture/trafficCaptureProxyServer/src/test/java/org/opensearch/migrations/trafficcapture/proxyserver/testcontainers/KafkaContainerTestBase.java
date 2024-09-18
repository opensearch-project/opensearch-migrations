package org.opensearch.migrations.trafficcapture.proxyserver.testcontainers;

import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

public class KafkaContainerTestBase extends TestContainerTestBase<KafkaContainer> {

    private static final KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
    );

    public KafkaContainer getContainer() {
        return kafka;
    }
}
