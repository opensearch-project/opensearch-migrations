package org.opensearch.migrations;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.opensearch.migrations.bulkload.solr.SolrHttpClient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Container-free unit tests for {@link SolrBackupStrategy#detectTopology}, covering the body/status/exception
 * branches (auth, unreachable, malformed) that the live-container tests in TestCreateSnapshotSolr can't reproduce.
 * The response shapes below are verbatim from Solr 6.6.0 through 9.8.1.
 */
public class SolrTopologyDetectionTest {

    private static final String URL = "http://solr:8983";

    /** SolrCloud's LIST reply (HTTP 200). */
    private static final String CLOUD_BODY =
        "{\"responseHeader\":{\"status\":0,\"QTime\":7},\"collections\":[\"movies\"]}";

    /** Standalone's rejection of the Collections API (HTTP 400). */
    private static final String STANDALONE_BODY =
        "{\"responseHeader\":{\"status\":400,\"QTime\":1},\"error\":{"
            + "\"metadata\":[\"error-class\",\"org.apache.solr.common.SolrException\"],"
            + "\"msg\":\"Solr instance is not running in SolrCloud mode.\",\"code\":400}}";

    @SuppressWarnings("unchecked")
    private static SolrHttpClient clientReturning(int statusCode, String body) throws Exception {
        var httpClient = mock(SolrHttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        when(httpClient.getRaw(anyString(), any(Duration.class))).thenReturn(response);
        return httpClient;
    }

    private static SolrHttpClient clientReturning(int statusCode) throws Exception {
        return clientReturning(statusCode, "");
    }

    private static SolrHttpClient clientThrowing(Exception e) throws Exception {
        var httpClient = mock(SolrHttpClient.class);
        when(httpClient.getRaw(anyString(), any(Duration.class))).thenThrow(e);
        return httpClient;
    }

    @Test
    void collectionsArray_isSolrCloud() throws Exception {
        assertTrue(SolrBackupStrategy.isSolrCloud(URL, clientReturning(200, CLOUD_BODY)));
        assertThat(SolrBackupStrategy.detectTopology(URL, clientReturning(200, CLOUD_BODY)),
            is(SolrBackupStrategy.SolrTopology.SOLR_CLOUD));
    }

    @Test
    void emptyCollectionsArray_isStillSolrCloud() throws Exception {
        // A cloud instance with no collections yet must not read as standalone.
        var body = "{\"responseHeader\":{\"status\":0},\"collections\":[]}";
        assertThat(SolrBackupStrategy.detectTopology(URL, clientReturning(200, body)),
            is(SolrBackupStrategy.SolrTopology.SOLR_CLOUD));
    }

    @Test
    void notRunningInSolrCloudMode_isStandalone() throws Exception {
        assertFalse(SolrBackupStrategy.isSolrCloud(URL, clientReturning(400, STANDALONE_BODY)));
        assertThat(SolrBackupStrategy.detectTopology(URL, clientReturning(400, STANDALONE_BODY)),
            is(SolrBackupStrategy.SolrTopology.STANDALONE));
    }

    @Test
    void usesTheCollectionsEndpoint() throws Exception {
        // Detection must stay on the endpoint discovery already uses, so it needs no extra permission.
        var httpClient = clientReturning(200, CLOUD_BODY);
        SolrBackupStrategy.detectTopology(URL, httpClient);
        org.mockito.Mockito.verify(httpClient).getRaw(
            org.mockito.ArgumentMatchers.eq(URL + "/solr/admin/collections?action=LIST&wt=json"),
            any(Duration.class));
    }

    @Test
    void bareStatus400WithoutTheMarker_isUnknown() throws Exception {
        // A 400 from something else (bad param, proxy) must not be read as standalone.
        var body = "{\"responseHeader\":{\"status\":400},\"error\":{\"msg\":\"unknown parameter\",\"code\":400}}";
        assertThat(SolrBackupStrategy.detectTopology(URL, clientReturning(400, body)),
            is(SolrBackupStrategy.SolrTopology.UNKNOWN));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not json at all", "{\"collections\":", "<html>proxy error</html>"})
    void unusableBody_isUnknown(String body) throws Exception {
        assertThat(SolrBackupStrategy.detectTopology(URL, clientReturning(200, body)),
            is(SolrBackupStrategy.SolrTopology.UNKNOWN));
    }

    @Test
    void nullBody_isUnknown() throws Exception {
        assertThat(SolrBackupStrategy.detectTopology(URL, clientReturning(200, null)),
            is(SolrBackupStrategy.SolrTopology.UNKNOWN));
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 404, 500, 503})
    void reachableButUninformativeStatus_isUnknown(int status) throws Exception {
        assertThat(SolrBackupStrategy.detectTopology(URL, clientReturning(status)),
            is(SolrBackupStrategy.SolrTopology.UNKNOWN));
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 404, 500, 503})
    void unknownTopology_throwsInsteadOfAssumingStandalone(int status) throws Exception {
        var ex = assertThrows(SolrBackupStrategy.SolrTopologyDetectionException.class,
            () -> SolrBackupStrategy.isSolrCloud(URL, clientReturning(status)));
        assertThat(ex.getMessage(), containsString(URL));
    }

    @Test
    void http401_throwsInsteadOfGuessing() throws Exception {
        var ex = assertThrows(SolrBackupStrategy.SolrTopologyDetectionException.class,
            () -> SolrBackupStrategy.isSolrCloud(URL, clientReturning(401)));
        assertThat(ex.getMessage(), containsString("401"));
        assertThat(ex.getMessage(), containsString("credentials"));
    }

    @Test
    void http403_namesTheRequiredPermission() throws Exception {
        var ex = assertThrows(SolrBackupStrategy.SolrTopologyDetectionException.class,
            () -> SolrBackupStrategy.isSolrCloud(URL, clientReturning(403)));
        assertThat(ex.getMessage(), containsString("403"));
        assertThat(ex.getMessage(), containsString("collection-admin-read"));
    }

    @Test
    void ioException_throwsInsteadOfGuessingStandalone() throws Exception {
        var ex = assertThrows(SolrBackupStrategy.SolrTopologyDetectionException.class,
            () -> SolrBackupStrategy.isSolrCloud(URL, clientThrowing(new IOException("connection refused"))));
        assertThat(ex.getMessage(), containsString(URL));
        assertThat(ex.getMessage(), containsString("connection refused"));
    }

    @Test
    void interrupted_throwsAndRestoresInterruptFlag() throws Exception {
        var client = clientThrowing(new InterruptedException("interrupted"));
        try {
            assertThrows(SolrBackupStrategy.SolrTopologyDetectionException.class,
                () -> SolrBackupStrategy.isSolrCloud(URL, client));
            assertTrue(Thread.currentThread().isInterrupted(),
                "InterruptedException must restore the thread's interrupt flag");
        } finally {
            // Clear the interrupt flag so it doesn't leak into other tests on this thread.
            Thread.interrupted();
        }
    }
}
