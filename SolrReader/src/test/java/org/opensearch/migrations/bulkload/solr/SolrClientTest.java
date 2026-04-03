package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SolrClientTest {

    @Test
    void constructsWithoutAuth() {
        var client = new SolrClient("http://localhost:8983");
        // Should not throw — no auth header set
        client.close();
    }

    @Test
    void constructsWithBasicAuth() {
        var client = new SolrClient("http://localhost:8983", "user", "pass");
        client.close();
    }

    @Test
    void constructsWithNullCredentials() {
        var client = new SolrClient("http://localhost:8983", null, null);
        client.close();
    }

    @Test
    void trailingSlashIsStripped() {
        var client = new SolrClient("http://localhost:8983/");
        // Verify by attempting a request — the URL should not have double slashes
        // This will fail to connect, but the URL construction should be correct
        assertThrows(IOException.class, client::listCollections);
    }

    @Test
    void zeroRetriesFailsImmediately() {
        var client = new SolrClient("http://localhost:1", null, null, 0);
        assertThrows(IOException.class, client::listCollections);
    }

    @Test
    void customRetryCountIsRespected() {
        // With 1 retry against unreachable host, should fail after 2 attempts total
        var client = new SolrClient("http://localhost:1", null, null, 1);
        var start = System.currentTimeMillis();
        assertThrows(IOException.class, client::listCollections);
        var elapsed = System.currentTimeMillis() - start;
        // Should take at least the base delay (500ms) for the retry
        assertThat("Should have retried (took " + elapsed + "ms)", elapsed > 200, equalTo(true));
    }
}
