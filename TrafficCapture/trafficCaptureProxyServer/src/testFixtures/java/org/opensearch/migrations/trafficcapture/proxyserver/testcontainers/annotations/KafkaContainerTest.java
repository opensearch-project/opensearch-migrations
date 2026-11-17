package org.opensearch.migrations.trafficcapture.proxyserver.testcontainers.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.parallel.ResourceLock;

@Inherited
@ResourceLock("KafkaContainer")
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE })
@TestContainerTest
public @interface KafkaContainerTest {

}
