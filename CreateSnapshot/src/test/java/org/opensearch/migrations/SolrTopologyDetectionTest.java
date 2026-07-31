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
 * Container-free unit tests for {@link SolrBackupStrategy#detectTopology}, covering the mode/status/exception
 * branches (auth, unreachable, malformed) that the live-container tests in TestCreateSnapshotSolr can't reproduce.
 */
public class SolrTopologyDetectionTest {

    private static final String URL = "http://solr:8983";

    private static String systemInfo(String mode) {
        return "{\"responseHeader\":{\"status\":0,\"QTime\":2},"
            + "\"mode\":\"" + mode + "\",\"solr_home\":\"/var/solr/data\"}";
    }

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
    void modeSolrCloud_isSolrCloud() throws Exception {
        assertTrue(SolrBackupStrategy.isSolrCloud(URL, clientReturning(200, systemInfo("solrcloud"))));
        assertThat(SolrBackupStrategy.detectTopology(URL, clientReturning(200, systemInfo("solrcloud"))),
            is(SolrBackupStrategy.SolrTopology.SOLR_CLOUD));
    }

    @Test
    void modeStd_isStandalone() throws Exception {
        assertFalse(SolrBackupStrategy.isSolrCloud(URL, clientReturning(200, systemInfo("std"))));
        assertThat(SolrBackupStrategy.detectTopology(URL, clientReturning(200, systemInfo("std"))),
            is(SolrBackupStrategy.SolrTopology.STANDALONE));
    }

    @Test
    void queriesTheSystemInfoEndpoint() throws Exception {
        var httpClient = clientReturning(200, systemInfo("std"));
        SolrBackupStrategy.detectTopology(URL, httpClient);
        org.mockito.Mockito.verify(httpClient)
            .getRaw(org.mockito.ArgumentMatchers.eq(URL + "/solr/admin/info/system?wt=json"), any(Duration.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SolrCloud", "STD"})
    void modeIsCaseInsensitive(String mode) throws Exception {
        var expected = mode.equalsIgnoreCase("std")
            ? SolrBackupStrategy.SolrTopology.STANDALONE
            : SolrBackupStrategy.SolrTopology.SOLR_CLOUD;
        assertThat(SolrBackupStrategy.detectTopology(URL, clientReturning(200, systemInfo(mode))), is(expected));
    }

    @Test
    void unrecognizedMode_isUnknown() throws Exception {
        assertThat(SolrBackupStrategy.detectTopology(URL, clientReturning(200, systemInfo("something-else"))),
            is(SolrBackupStrategy.SolrTopology.UNKNOWN));
    }

    @Test
    void missingModeProperty_isUnknown() throws Exception {
        assertThat(SolrBackupStrategy.detectTopology(URL, clientReturning(200, "{\"solr_home\":\"/var/solr\"}")),
            is(SolrBackupStrategy.SolrTopology.UNKNOWN));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not json at all", "{\"mode\":"})
    void unusableBody_isUnknown(String body) throws Exception {
        // A 200 that isn't parseable JSON must not be read as either topology.
        assertThat(SolrBackupStrategy.detectTopology(URL, clientReturning(200, body)),
            is(SolrBackupStrategy.SolrTopology.UNKNOWN));
    }

    @Test
    void nullBody_isUnknown() throws Exception {
        assertThat(SolrBackupStrategy.detectTopology(URL, clientReturning(200, null)),
            is(SolrBackupStrategy.SolrTopology.UNKNOWN));
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 400, 404, 500, 503})
    void reachableButUninformativeStatus_isUnknown(int status) throws Exception {
        assertThat(SolrBackupStrategy.detectTopology(URL, clientReturning(status)),
            is(SolrBackupStrategy.SolrTopology.UNKNOWN));
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 400, 404, 500, 503})
    void unknownTopology_throwsInsteadOfAssumingStandalone(int status) throws Exception {
        var ex = assertThrows(SolrBackupStrategy.SolrTopologyDetectionException.class,
            () -> SolrBackupStrategy.isSolrCloud(URL, clientReturning(status)));
        assertThat(ex.getMessage(), containsString(URL));
    }

    @Test
    void nonOkStatusIsNotParsedForMode() throws Exception {
        // A body that says "solrcloud" alongside an error status must not be trusted.
        assertThat(SolrBackupStrategy.detectTopology(URL, clientReturning(500, systemInfo("solrcloud"))),
            is(SolrBackupStrategy.SolrTopology.UNKNOWN));
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
        // The system info endpoint needs 'config-read', which the other Solr calls don't — say so.
        var ex = assertThrows(SolrBackupStrategy.SolrTopologyDetectionException.class,
            () -> SolrBackupStrategy.isSolrCloud(URL, clientReturning(403)));
        assertThat(ex.getMessage(), containsString("403"));
        assertThat(ex.getMessage(), containsString("config-read"));
        assertThat(ex.getMessage(), containsString("/solr/admin/info/system"));
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
