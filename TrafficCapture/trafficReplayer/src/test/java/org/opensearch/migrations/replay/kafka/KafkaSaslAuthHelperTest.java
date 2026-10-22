package org.opensearch.migrations.replay.kafka;

import java.util.Properties;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class KafkaSaslAuthHelperTest {

    @Test
    void sslAuthSelectsTlsWithoutSasl() {
        var properties = new Properties();

        KafkaSaslAuthHelper.applySaslAuthProperties(properties, "ssl", null, null);

        Assertions.assertEquals("SSL", properties.getProperty("security.protocol"));
        Assertions.assertNull(properties.getProperty("sasl.mechanism"));
        Assertions.assertNull(properties.getProperty("sasl.jaas.config"));
    }
}
