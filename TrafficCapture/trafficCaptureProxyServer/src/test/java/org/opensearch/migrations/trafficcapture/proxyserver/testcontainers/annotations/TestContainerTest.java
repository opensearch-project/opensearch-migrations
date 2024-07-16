package org.opensearch.migrations.trafficcapture.proxyserver.testcontainers.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;

import org.testcontainers.junit.jupiter.Testcontainers;

@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE })
@Tag("longTest")
@Testcontainers(disabledWithoutDocker = true, parallel = true)
public @interface TestContainerTest {
}
