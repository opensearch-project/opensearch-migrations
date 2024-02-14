package org.opensearch.migrations.trafficcapture.proxyserver.testcontainers.annotations;

import java.lang.annotation.Inherited;
import org.junit.jupiter.api.Tag;
import org.testcontainers.junit.jupiter.Testcontainers;

@Inherited
@Tag("longTest")
@Testcontainers(disabledWithoutDocker = true, parallel = true)
public @interface TestContainerTest {
}
