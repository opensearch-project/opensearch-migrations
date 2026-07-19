package org.opensearch.migrations;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.opensearch.migrations.bulkload.solr.SolrHttpClient;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fast, container-free unit tests for {@link SolrBackupStrategy#isSolrCloud}. The real cloud/standalone
 * cases are also exercised end-to-end against live containers in TestCreateSnapshotSolr; these cover the
 * status/exception branches (auth, unreachable) that containers cannot easily reproduce.
 */
public class SolrTopologyDetectionTest {

    private static final String URL = "http://solr:8983";

    @SuppressWarnings("unchecked")
    private static SolrHttpClient clientReturning(int statusCode) throws Exception {
        var httpClient = mock(SolrHttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(httpClient.getRaw(anyString(), any(Duration.class))).thenReturn(response);
        return httpClient;
    }

    private static SolrHttpClient clientThrowing(Exception e) throws Exception {
        var httpClient = mock(SolrHttpClient.class);
        when(httpClient.getRaw(anyString(), any(Duration.class))).thenThrow(e);
        return httpClient;
    }

    @Test
    void http200_isSolrCloud() throws Exception {
        assertTrue(SolrBackupStrategy.isSolrCloud(URL, clientReturning(200)));
    }

    @Test
    void http400_isStandalone() throws Exception {
        // Standalone Solr answers the Collections API with HTTP 400 "not running in SolrCloud mode".
        assertFalse(SolrBackupStrategy.isSolrCloud(URL, clientReturning(400)));
    }

    @Test
    void http404_isStandalone() throws Exception {
        assertFalse(SolrBackupStrategy.isSolrCloud(URL, clientReturning(404)));
    }

    @Test
    void http401_throwsInsteadOfGuessing() throws Exception {
        var ex = assertThrows(SolrBackupStrategy.SolrTopologyDetectionException.class,
            () -> SolrBackupStrategy.isSolrCloud(URL, clientReturning(401)));
        assertThat(ex.getMessage(), containsString("401"));
    }

    @Test
    void http403_throwsInsteadOfGuessing() throws Exception {
        assertThrows(SolrBackupStrategy.SolrTopologyDetectionException.class,
            () -> SolrBackupStrategy.isSolrCloud(URL, clientReturning(403)));
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
