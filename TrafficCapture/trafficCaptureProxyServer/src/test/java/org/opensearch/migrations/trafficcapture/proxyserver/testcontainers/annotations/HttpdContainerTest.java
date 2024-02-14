package org.opensearch.migrations.trafficcapture.proxyserver.testcontainers.annotations;

import java.lang.annotation.Inherited;
import org.junit.jupiter.api.parallel.ResourceLock;

@Inherited
@ResourceLock("HttpdContainer")
@TestContainerTest
public @interface HttpdContainerTest {

}
