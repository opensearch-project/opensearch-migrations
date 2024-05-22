package com.rfs.common;

import static org.junit.jupiter.api.Assertions.*;

class RestClientTest {
    public void testGetEmitsInstrumentation() {
        try (var testServer = SimpleNettyHttpServer.makeServer(useTls,
                withServerReadTimeout ? readTimeout : null,
                NettyPacketToHttpConsumerTest::makeResponseContext)) {

        }
}