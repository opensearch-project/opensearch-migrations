package com.rfs.common;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ConnectionDetailsTest {
    static Stream<Arguments> happyPathArgs() {
        return Stream.of(
            Arguments.of(
                "https://localhost:9200",
                "username",
                "pass",
                "https://localhost:9200",
                "username",
                "pass",
                ConnectionDetails.Protocol.HTTPS,
                "localhost",
                9200
            ),
            Arguments.of(
                "http://localhost:9200",
                "username",
                "pass",
                "http://localhost:9200",
                "username",
                "pass",
                ConnectionDetails.Protocol.HTTP,
                "localhost",
                9200
            ),
            Arguments.of(
                "http://localhost:9200",
                null,
                null,
                "http://localhost:9200",
                null,
                null,
                ConnectionDetails.Protocol.HTTP,
                "localhost",
                9200
            ),
            Arguments.of(
                "http://localhost",
                "username",
                "pass",
                "http://localhost",
                "username",
                "pass",
                ConnectionDetails.Protocol.HTTP,
                "localhost",
                -1
            ),
            Arguments.of(
                "http://localhost:9200/longer/path",
                "username",
                "pass",
                "http://localhost:9200/longer/path",
                "username",
                "pass",
                ConnectionDetails.Protocol.HTTP,
                "localhost",
                9200
            ),
            Arguments.of(null, "username", "pass", null, "username", "pass", null, null, -1)
        );
    }

    @ParameterizedTest
    @MethodSource("happyPathArgs")
    void ConnectionDetails_HappyPath_AsExpected(
        String url,
        String username,
        String password,
        String expectedUrl,
        String expectedUsername,
        String expectedPassword,
        ConnectionDetails.Protocol expectedProtocal,
        String expectedHostName,
        int expectedPort
    ) {
        ConnectionDetails details = new ConnectionDetails(url, username, password);
        assertEquals(expectedUrl, details.url);
        assertEquals(expectedUsername, details.username);
        assertEquals(expectedPassword, details.password);
        assertEquals(expectedProtocal, details.protocol);
        assertEquals(expectedHostName, details.hostName);
        assertEquals(expectedPort, details.port);
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
    void ConnectionDetails_UnhappyPath_AsExpected(
        String url,
        String username,
        String password,
        Class<Exception> expectedException
    ) {
        assertThrows(expectedException, () -> new ConnectionDetails(url, username, password));
    }
}
