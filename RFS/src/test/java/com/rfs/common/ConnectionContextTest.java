package com.rfs.common;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.rfs.common.http.ConnectionContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ConnectionContextTest {
    static Stream<Arguments> happyPathArgs() {
        return Stream.of(
            Arguments.of(
                "https://localhost:9200",
                "username",
                "pass",
                "https://localhost:9200",
                ConnectionContext.Protocol.HTTPS,
                "localhost",
                9200
            ),
            Arguments.of(
                "http://localhost:9200",
                "username",
                "pass",
                "http://localhost:9200",
                ConnectionContext.Protocol.HTTP,
                "localhost",
                9200
            ),
            Arguments.of(
                "http://localhost:9200",
                null,
                null,
                "http://localhost:9200",
                ConnectionContext.Protocol.HTTP,
                "localhost",
                9200
            ),
            Arguments.of(
                "http://localhost",
                "username",
                "pass",
                "http://localhost",
                ConnectionContext.Protocol.HTTP,
                "localhost",
                -1
            ),
            Arguments.of(
                "http://localhost:9200/longer/path",
                "username",
                "pass",
                "http://localhost:9200/longer/path",
                ConnectionContext.Protocol.HTTP,
                "localhost",
                9200
            ),
            Arguments.of(null, "username", "pass", null, null, null, -1)
        );
    }

    @ParameterizedTest
    @MethodSource("happyPathArgs")
    void ConnectionContext_HappyPath_AsExpected(
        String url,
        String username,
        String password,
        String expectedUrl,
        ConnectionContext.Protocol expectedProtocol,
        String expectedHostName,
        int expectedPort
    ) {
        ConnectionContext details = new ConnectionContext(url, username, password);
        assertEquals(expectedUrl, details.url);
        assertEquals(expectedProtocol, details.protocol);
        assertEquals(expectedHostName, details.hostName);
        assertEquals(expectedPort, details.port);
        assertNotNull(details.getAuthTransformer());
    }

    static Stream<Arguments> unhappyPathArgs() {
        return Stream.of(
            Arguments.of("localhost:9200", "username", "pass", IllegalArgumentException.class),
            Arguments.of("http://localhost:9200", "username", null, IllegalArgumentException.class),
            Arguments.of("http://localhost:9200", null, "pass", IllegalArgumentException.class),
            Arguments.of("ftp://localhost:9200", null, "pass", IllegalArgumentException.class)
        );
    }

    @ParameterizedTest
    @MethodSource("unhappyPathArgs")
    void ConnectionContext_UnhappyPath_AsExpected(
        String url,
        String username,
        String password,
        Class<Exception> expectedException
    ) {
        assertThrows(expectedException, () -> new ConnectionContext(url, username, password));
    }
}
