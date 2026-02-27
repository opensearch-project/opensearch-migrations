package org.opensearch.migrations.bulkload.common.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionContextTest {

    private ConnectionContext create(String url) {
        var args = new ConnectionContext.TargetArgs();
        args.host = url;
        return args.toConnectionContext();
    }

    @Test
    void httpUrl_setsProtocolCorrectly() {
        var ctx = create("http://localhost:9200");
        assertEquals(ConnectionContext.Protocol.HTTP, ctx.getProtocol());
        assertEquals("localhost", ctx.getUri().getHost());
        assertEquals(9200, ctx.getUri().getPort());
    }

    @Test
    void httpsUrl_setsProtocolCorrectly() {
        var ctx = create("https://search.example.com");
        assertEquals(ConnectionContext.Protocol.HTTPS, ctx.getProtocol());
        assertFalse(ctx.isInsecure());
    }

    @Test
    void nullHost_throws() {
        assertThrows(IllegalArgumentException.class, () -> create(null));
    }

    @Test
    void invalidScheme_throws() {
        assertThrows(IllegalArgumentException.class, () -> create("ftp://localhost"));
    }

    @Test
    void basicAuth_setsTransformer() {
        var args = new ConnectionContext.TargetArgs();
        args.host = "http://localhost:9200";
        args.username = "admin";
        args.password = "secret";
        var ctx = args.toConnectionContext();
        assertNotNull(ctx.getRequestTransformer());
        assertFalse(ctx.isAwsSpecificAuthentication());
    }

    @Test
    void usernameWithoutPassword_throws() {
        var args = new ConnectionContext.TargetArgs();
        args.host = "http://localhost:9200";
        args.username = "admin";
        assertThrows(IllegalArgumentException.class, args::toConnectionContext);
    }

    @Test
    void noAuth_setsNoAuthTransformer() {
        var ctx = create("http://localhost:9200");
        assertNotNull(ctx.getRequestTransformer());
        assertFalse(ctx.isAwsSpecificAuthentication());
    }

    @Test
    void insecureFlag_isRespected() {
        var args = new ConnectionContext.TargetArgs();
        args.host = "https://localhost:9200";
        args.insecure = true;
        var ctx = args.toConnectionContext();
        assertTrue(ctx.isInsecure());
    }

    @Test
    void toUserFacingData_containsExpectedKeys() {
        var ctx = create("http://localhost:9200");
        var data = ctx.toUserFacingData();
        assertTrue(data.containsKey("Uri"));
        assertTrue(data.containsKey("Protocol"));
        assertTrue(data.containsKey("TLS Verification"));
    }
}
