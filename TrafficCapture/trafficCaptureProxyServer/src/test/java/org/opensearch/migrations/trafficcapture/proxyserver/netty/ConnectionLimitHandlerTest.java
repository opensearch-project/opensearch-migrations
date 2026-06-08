package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ConnectionLimitHandlerTest {

    @Test
    void testConnectionsWithinLimitAreAccepted() {
        var handler = new ConnectionLimitHandler(2);
        var ch1 = new EmbeddedChannel(handler);
        var ch2 = new EmbeddedChannel(handler);

        Assertions.assertTrue(ch1.isActive());
        Assertions.assertTrue(ch2.isActive());
        Assertions.assertEquals(2, handler.getActiveConnections());

        ch1.close();
        ch2.close();
    }

    @Test
    void testConnectionExceedingLimitIsRejectedWith503() {
        var handler = new ConnectionLimitHandler(1);
        var ch1 = new EmbeddedChannel(handler);
        Assertions.assertTrue(ch1.isActive());
        Assertions.assertEquals(1, handler.getActiveConnections());

        // Second connection should be rejected
        var ch2 = new EmbeddedChannel(handler);
        // The channel writes a 503 response and closes
        ByteBuf response = ch2.readOutbound();
        Assertions.assertNotNull(response);
        String responseStr = response.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(responseStr.contains("503 Service Unavailable"));
        response.release();

        // ch1 is still active and counted
        Assertions.assertTrue(ch1.isActive());

        ch1.close();
    }

    @Test
    void testConnectionCountDecrementsOnClose() {
        var handler = new ConnectionLimitHandler(5);
        var ch1 = new EmbeddedChannel(handler);
        var ch2 = new EmbeddedChannel(handler);
        Assertions.assertEquals(2, handler.getActiveConnections());

        ch1.close();
        Assertions.assertEquals(1, handler.getActiveConnections());

        ch2.close();
        Assertions.assertEquals(0, handler.getActiveConnections());
    }

    @Test
    void testNewConnectionAllowedAfterPreviousCloses() {
        var handler = new ConnectionLimitHandler(1);
        var ch1 = new EmbeddedChannel(handler);
        Assertions.assertEquals(1, handler.getActiveConnections());

        ch1.close();
        Assertions.assertEquals(0, handler.getActiveConnections());

        // Should be able to accept a new connection now
        var ch2 = new EmbeddedChannel(handler);
        Assertions.assertTrue(ch2.isActive());
        Assertions.assertEquals(1, handler.getActiveConnections());

        ch2.close();
    }
}
