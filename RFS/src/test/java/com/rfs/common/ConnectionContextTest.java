package com.rfs.common;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.rfs.common.http.BasicAuthTransformer;
import com.rfs.common.http.ConnectionContext;
import com.rfs.common.http.ConnectionContextTestParams;
import com.rfs.common.http.NoAuthTransformer;
import com.rfs.common.http.SigV4AuthTransformer;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionContextTest {

    @ParameterizedTest
    @MethodSource("validConnectionParams")
    void testValidConnectionContextCreation(ConnectionContextTestParams params,
                                            ConnectionContext.Protocol expectedProtocol,
                                            String expectedHostName,
                                            int expectedPort,
                                            Class<?> expectedAuthTransformerClass,
                                            boolean expectedInsecure) {
        ConnectionContext context = params.toConnectionContext();

        assertEquals(params.getHost(), context.getUrl());
        assertEquals(expectedProtocol, context.protocol);
        assertEquals(expectedHostName, context.hostName);
        assertEquals(expectedPort, context.port);
        assertEquals(expectedInsecure, context.insecure);
        assertTrue(expectedAuthTransformerClass.isInstance(context.getAuthTransformer()));
    }

    private static Stream<Arguments> validConnectionParams() {
        return Stream.of(
            Arguments.of(
                ConnectionContextTestParams.builder()
                    .host("http://localhost:9200")
                    .build(),
                ConnectionContext.Protocol.HTTP,
                "localhost",
                9200,
                NoAuthTransformer.class,
                true
            ),
            Arguments.of(
                ConnectionContextTestParams.builder()
                    .host("https://example.com:443")
                    .username("user")
                    .password("pass")
                    .build(),
                ConnectionContext.Protocol.HTTPS,
                "example.com",
                443,
                BasicAuthTransformer.class,
                true
            ),
            Arguments.of(
                ConnectionContextTestParams.builder()
                    .host("https://opensearch.us-east-1.amazonaws.com")
                    .awsRegion("us-east-1")
                    .awsServiceSigningName("es")
                    .build(),
                ConnectionContext.Protocol.HTTPS,
                "opensearch.us-east-1.amazonaws.com",
                -1,
                SigV4AuthTransformer.class,
                true
            ),
            Arguments.of(
                ConnectionContextTestParams.builder()
                    .host("https://secure.example.com")
                    .insecure(false)
                    .build(),
                ConnectionContext.Protocol.HTTPS,
                "secure.example.com",
                -1,
                NoAuthTransformer.class,
                false
            ),
            Arguments.of(
                ConnectionContextTestParams.builder()
                    .host("http://insecure.example.com")
                    .insecure(true)
                    .build(),
                ConnectionContext.Protocol.HTTP,
                "insecure.example.com",
                -1,
                NoAuthTransformer.class,
                true
            )
        );
    }

    @ParameterizedTest
    @MethodSource("invalidConnectionParams")
    void testInvalidConnectionContextCreation(ConnectionContextTestParams params, Class<? extends Exception> expectedException) {
        assertThrows(expectedException, params::toConnectionContext);
    }

    private static Stream<Arguments> invalidConnectionParams() {
        return Stream.of(
            Arguments.of(
                ConnectionContextTestParams.builder()
                    .host("ftp://invalid.com")
                    .build(),
                IllegalArgumentException.class
            ),
            Arguments.of(
                ConnectionContextTestParams.builder()
                    .host("http://example.com")
                    .username("user")
                    .build(),
                IllegalArgumentException.class
            ),
            Arguments.of(
                ConnectionContextTestParams.builder()
                    .host("http://example.com")
                    .awsRegion("us-east-1")
                    .build(),
                IllegalArgumentException.class
            ),
            Arguments.of(
                ConnectionContextTestParams.builder()
                    .host("http://example.com")
                    .username("user")
                    .password("pass")
                    .awsRegion("us-east-1")
                    .awsServiceSigningName("es")
                    .build(),
                IllegalArgumentException.class
            ),
            Arguments.of(
                ConnectionContextTestParams.builder()
                    .host("http://example.com")
                    .password("pass")
                    .build(),
                IllegalArgumentException.class
            ),
            Arguments.of(
                ConnectionContextTestParams.builder()
                    .host("http://example.com")
                    .awsServiceSigningName("es")
                    .build(),
                IllegalArgumentException.class
            )
        );
    }
}
