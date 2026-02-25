package org.opensearch.migrations.bulkload.common;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.http.BasicAuthTransformer;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.common.http.NoAuthTransformer;
import org.opensearch.migrations.bulkload.common.http.SigV4AuthTransformer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionContextTest {

    @ParameterizedTest
    @MethodSource("validConnectionParams")
    void testValidConnectionContextCreation(ConnectionContextTestParams params,
                                            ConnectionContext.Protocol expectedProtocol,
                                            Class<?> expectedAuthTransformerClass,
                                            boolean expectedInsecure) {
        ConnectionContext context = params.toConnectionContext();

        assertEquals(params.getHost(), context.getUri().toString());
        assertEquals(expectedProtocol, context.getProtocol());
        assertEquals(expectedInsecure, context.isInsecure());
        assertTrue(expectedAuthTransformerClass.isInstance(context.getRequestTransformer()));
    }

    private static Stream<Arguments> validConnectionParams() {
        return Stream.of(
            Arguments.of(
                ConnectionContextTestParams.builder()
                    .host("http://localhost:9200")
                    .build(),
                ConnectionContext.Protocol.HTTP,
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
                SigV4AuthTransformer.class,
                true
            ),
            Arguments.of(
                ConnectionContextTestParams.builder()
                    .host("https://secure.example.com")
                    .insecure(false)
                    .build(),
                ConnectionContext.Protocol.HTTPS,
                NoAuthTransformer.class,
                false
            ),
            Arguments.of(
                ConnectionContextTestParams.builder()
                    .host("http://insecure.example.com")
                    .insecure(true)
                    .build(),
                ConnectionContext.Protocol.HTTP,
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

    @Test
    void testTargetArgsDefaultValues() {
        ConnectionContext.TargetArgs targetArgs = new ConnectionContext.TargetArgs();
        assertNull(targetArgs.getUsername());
        assertNull(targetArgs.getPassword());
        assertNull(targetArgs.getAwsRegion());
        assertNull(targetArgs.getAwsServiceSigningName());
        assertFalse(targetArgs.isInsecure());
        assertFalse(targetArgs.isDisableCompression());
    }

    @Test
    void testSourceArgsDefaultValues() {
        ConnectionContext.SourceArgs sourceArgs = new ConnectionContext.SourceArgs();
        assertNull(sourceArgs.getHost());
        assertNull(sourceArgs.getUsername());
        assertNull(sourceArgs.getPassword());
        assertNull(sourceArgs.getAwsRegion());
        assertNull(sourceArgs.getAwsServiceSigningName());
        assertFalse(sourceArgs.isInsecure());
        assertTrue(sourceArgs.isDisableCompression());
    }

    @Test
    void testCoordinatorArgsDefaultValues() {
        ConnectionContext.CoordinatorArgs coordinatorArgs = new ConnectionContext.CoordinatorArgs();
        assertNull(coordinatorArgs.getHost());
        assertNull(coordinatorArgs.getUsername());
        assertNull(coordinatorArgs.getPassword());
        assertNull(coordinatorArgs.getAwsRegion());
        assertNull(coordinatorArgs.getAwsServiceSigningName());
        assertNull(coordinatorArgs.getCaCert());
        assertNull(coordinatorArgs.getClientCert());
        assertNull(coordinatorArgs.getClientCertKey());
        assertFalse(coordinatorArgs.isInsecure());
        assertTrue(coordinatorArgs.isDisableCompression());
    }

    @Test
    void testCoordinatorArgsIsEnabled_WhenHostIsNull_ReturnsFalse() {
        ConnectionContext.CoordinatorArgs coordinatorArgs = new ConnectionContext.CoordinatorArgs();
        assertFalse(coordinatorArgs.isEnabled());
    }

    @Test
    void testCoordinatorArgsIsEnabled_WhenHostIsSet_ReturnsTrue() {
        ConnectionContext.CoordinatorArgs coordinatorArgs = new ConnectionContext.CoordinatorArgs();
        coordinatorArgs.host = "http://coordinator:9200";
        assertTrue(coordinatorArgs.isEnabled());
    }

    @Test
    void testCoordinatorArgsToConnectionContext_WithNoAuth() {
        ConnectionContext.CoordinatorArgs coordinatorArgs = new ConnectionContext.CoordinatorArgs();
        coordinatorArgs.host = "http://coordinator:9200";

        ConnectionContext context = coordinatorArgs.toConnectionContext();

        assertEquals("http://coordinator:9200", context.getUri().toString());
        assertEquals(ConnectionContext.Protocol.HTTP, context.getProtocol());
        assertTrue(context.getRequestTransformer() instanceof NoAuthTransformer);
    }

    @Test
    void testCoordinatorArgsToConnectionContext_WithBasicAuth() {
        ConnectionContext.CoordinatorArgs coordinatorArgs = new ConnectionContext.CoordinatorArgs();
        coordinatorArgs.host = "https://coordinator:9200";
        coordinatorArgs.username = "admin";
        coordinatorArgs.password = "secret";

        ConnectionContext context = coordinatorArgs.toConnectionContext();

        assertEquals("https://coordinator:9200", context.getUri().toString());
        assertEquals(ConnectionContext.Protocol.HTTPS, context.getProtocol());
        assertTrue(context.getRequestTransformer() instanceof BasicAuthTransformer);
    }

    @Test
    void testCoordinatorArgsToConnectionContext_WithSigV4Auth() {
        ConnectionContext.CoordinatorArgs coordinatorArgs = new ConnectionContext.CoordinatorArgs();
        coordinatorArgs.host = "https://coordinator.us-east-1.amazonaws.com";
        coordinatorArgs.awsRegion = "us-east-1";
        coordinatorArgs.awsServiceSigningName = "es";

        ConnectionContext context = coordinatorArgs.toConnectionContext();

        assertEquals("https://coordinator.us-east-1.amazonaws.com", context.getUri().toString());
        assertEquals(ConnectionContext.Protocol.HTTPS, context.getProtocol());
        assertTrue(context.getRequestTransformer() instanceof SigV4AuthTransformer);
    }

    @Test
    void testCoordinatorArgsToConnectionContext_FailsWithPartialBasicAuth() {
        ConnectionContext.CoordinatorArgs coordinatorArgs = new ConnectionContext.CoordinatorArgs();
        coordinatorArgs.host = "http://coordinator:9200";
        coordinatorArgs.username = "admin";
        // password not set

        assertThrows(IllegalArgumentException.class, coordinatorArgs::toConnectionContext);
    }

    @Test
    void testCoordinatorArgsToConnectionContext_FailsWithPartialSigV4Auth() {
        ConnectionContext.CoordinatorArgs coordinatorArgs = new ConnectionContext.CoordinatorArgs();
        coordinatorArgs.host = "http://coordinator:9200";
        coordinatorArgs.awsRegion = "us-east-1";
        // awsServiceSigningName not set

        assertThrows(IllegalArgumentException.class, coordinatorArgs::toConnectionContext);
    }
}
