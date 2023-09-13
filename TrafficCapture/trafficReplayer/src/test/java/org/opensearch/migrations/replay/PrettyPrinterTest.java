package org.opensearch.migrations.replay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PrettyPrinterTest {

    final static String EXPECTED_REQUEST_STRING =
            "GET / HTTP/1.1\r\n" +
                    "Connection: Keep-Alive\r\n" +
                    "Host: localhost\r\n" +
                    "User-Agent: UnitTest\r\n" +
                    "Connection: Keep-Alive\r\n" +
                    "\r\n";
    private final static String EXPECTED_RESPONSE_STRING =
            "HTTP/1.1 200 OK\r\n" +
                    "Content-transfer-encoding: chunked\r\n" +
                    "Date: Thu, 08 Jun 2023 23:06:23 GMT\r\n" + // This should be OK since it's always the same length
                    "Transfer-encoding: chunked\r\n" +
                    "Content-type: text/plain\r\n" +
                    "Funtime: checkIt!\r\n" +
                    "\r\n" +
                    "1e\r\n" +
                    "I should be decrypted tester!\n" +
                    "\r\n" +
                    "0\r\n" +
                    "\r\n";
    
    @Test
    void setPrintStyleForCallable() {
    }

    @Test
    void setPrintStyleFor() {
    }

    @Test
    void testSetPrintStyleFor() {
    }

    @Test
    void httpPacketBytesToString() {
    }

    @Test
    void testHttpPacketBytesToString() {
    }

    @Test
    void httpPacketBufsToString() {
    }

    @Test
    void httpPacketsToPrettyPrintedString() {
    }

    @Test
    void prettyPrintNettyRequest() {
    }

    @Test
    void prettyPrintNettyResponse() {
    }

    @Test
    void parseHttpMessageFromBufsWithoutReleasing() {
    }

    @Test
    void testHttpPacketBufsToString() {
    }
}