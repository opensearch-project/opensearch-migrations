package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.net.URI;
import java.time.Duration;

import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.handler.ssl.SslContextBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BacksideConnectionPoolTest {

    @Test
    void createsTlsEngineWithDestinationPeer() throws Exception {
        var destination = URI.create("https://search.example.test:9443");
        var connectionPool = new BacksideConnectionPool(
            destination,
            SslContextBuilder.forClient().build(),
            0,
            Duration.ZERO
        );

        var sslEngine = connectionPool.createSslEngine(UnpooledByteBufAllocator.DEFAULT);

        Assertions.assertEquals(destination.getHost(), sslEngine.getPeerHost());
        Assertions.assertEquals(destination.getPort(), sslEngine.getPeerPort());
    }
}
