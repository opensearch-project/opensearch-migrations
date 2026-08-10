package org.opensearch.migrations;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.opensearch.migrations.bulkload.solr.SolrHttpClient;

import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    // ---- Discovery doubles as detection, so no extra permission is needed ----

    private static final String CORES_BODY =
        "{\"responseHeader\":{\"status\":0},\"status\":{\"dummy\":{\"name\":\"dummy\"}}}";

    /**
     * The permission guarantee: once standalone is known, the Collections API is never requested, so
     * the source needs no 'collection-admin-read' grant.
     */
    @Test
    void knownStandalone_neverTouchesTheCollectionsApi() throws Exception {
        var client = mock(SolrHttpClient.class);
        when(client.getString(anyString(), any(Duration.class))).thenReturn(CORES_BODY);

        var discovered = SolrBackupStrategy.discoverCollections(URL, client, Boolean.FALSE);

        assertThat(discovered.names(), is(java.util.List.of("dummy")));
        assertThat(discovered.topology(), is(SolrBackupStrategy.SolrTopology.STANDALONE));
        verify(client, never()).getRaw(anyString(), any(Duration.class));
        verify(client).getString(contains("/admin/cores"), any(Duration.class));
    }

    @Test
    void discoveryClassifiesTopologyFromTheSameRequest() throws Exception {
        var client = clientReturning(200, CLOUD_BODY);

        var discovered = SolrBackupStrategy.discoverCollections(URL, client, null);

        assertThat(discovered.names(), is(java.util.List.of("movies")));
        assertThat(discovered.topology(), is(SolrBackupStrategy.SolrTopology.SOLR_CLOUD));
        // One request answered both questions.
        verify(client, times(1)).getRaw(anyString(), any(Duration.class));
    }

    @Test
    void standaloneRejection_fallsBackToCores() throws Exception {
        var client = clientReturning(400, STANDALONE_BODY);
        when(client.getString(anyString(), any(Duration.class))).thenReturn(CORES_BODY);

        var discovered = SolrBackupStrategy.discoverCollections(URL, client, null);

        assertThat(discovered.names(), is(java.util.List.of("dummy")));
        assertThat(discovered.topology(), is(SolrBackupStrategy.SolrTopology.STANDALONE));
    }

    /** A denied Collections API says nothing about topology, so it must not be guessed. */
    @Test
    void deniedCollectionsApi_doesNotFallBackToCores() throws Exception {
        var client = clientReturning(403, "");
        when(client.getString(anyString(), any(Duration.class))).thenReturn(CORES_BODY);

        assertThrows(SolrBackupStrategy.SolrTopologyDetectionException.class,
            () -> SolrBackupStrategy.discoverCollections(URL, client, null));
        verify(client, never()).getString(anyString(), any(Duration.class));
    }

    // ---- CREATE infers topology from the backup request itself ----

    /** Verbatim from SolrSnapshotCreator when a standalone source rejects the Collections API. */
    @Test
    void standaloneRejectionOfBackup_switchesToReplicationHandler() {
        assertTrue(SolrBackupStrategy.isNotSolrCloudRejection(
            "Backup initiation failed for collection 'dummy': Solr instance is not running in SolrCloud mode."));
    }

    /** The shape that actually reaches us: HTTP 400 via SolrRequestException. Verbatim from Solr 8. */
    @Test
    void standaloneRejectionArrivingAsAnHttpError_isRecognized() {
        assertTrue(SolrBackupStrategy.isNotSolrCloudRejection(
            "Solr returned HTTP 400 for http://localhost:32852/solr/admin/collections?action=BACKUP"
                + "&name=s3_meta_coll&collection=s3_meta_coll&async=x&wt=json — body: {\n"
                + "  \"responseHeader\":{\n    \"status\":400,\n    \"QTime\":0},\n"
                + "  \"error\":{\n    \"metadata\":[\n"
                + "      \"error-class\",\"org.apache.solr.common.SolrException\",\n"
                + "      \"root-error-class\",\"org.apache.solr.common.SolrException\"],\n"
                + "    \"msg\":\"Solr instance is not running in SolrCloud mode.\",\n"
                + "    \"code\":400}}"));
    }

    /** Auth failures reach us through the same exception type and must not redirect. */
    @ParameterizedTest
    @ValueSource(strings = {
        "Solr authentication failed (HTTP 401) for http://solr:8983/solr/admin/collections?action=BACKUP",
        "Solr authentication failed (HTTP 403) for http://solr:8983/solr/admin/collections?action=BACKUP",
        "Failed to communicate with Solr: Connection refused"
    })
    void transportAndAuthFailures_doNotLookLikeStandalone(String message) {
        assertFalse(SolrBackupStrategy.isNotSolrCloudRejection(message));
    }

    /** Anything other than a positive "not SolrCloud" rejection must surface, not silently redirect. */
    @ParameterizedTest
    @ValueSource(strings = {
        "Backup initiation failed for collection 'movies': Unauthorized",
        "Backup initiation failed for collection 'movies': no such repository 'my-repo'",
        "Backup initiation failed for collection 'movies': Collection 'movies' does not exist",
        "Backup initiation failed for collection 'movies': SolrCloud backup timed out"
    })
    void otherBackupFailures_doNotLookLikeStandalone(String message) {
        assertFalse(SolrBackupStrategy.isNotSolrCloudRejection(message));
    }

    @Test
    void nullBackupMessage_doesNotLookLikeStandalone() {
        assertFalse(SolrBackupStrategy.isNotSolrCloudRejection(null));
    }

    // ---- Topology inferred from the backup itself (no Solr request at all) ----

    /**
     * Only markers this class cannot itself write count as SolrCloud. Notably absent: zk_backup*,
     * which the standalone path synthesizes — see standaloneLayout_hasNoCloudMarkers.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "movies/backup.properties",
        "movies/backup_0.properties",
        "movies/movies/backup.properties",
        "movies/shard_backup_metadata/md_shard1_0",
        "movies/movies/shard_backup_metadata/md_shard1_0"
    })
    void cloudMarkers_areRecognized(String path) {
        assertTrue(SolrBackupStrategy.hasCloudBackupMarker(path), path + " is a SolrCloud-only marker");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "dummy/snapshot.dummy-20240101/segments_1",
        "dummy/snapshot.dummy/_0.cfs",
        "dummy/backups/segments_1",
        "backup_index/segments_1",
        // The synthetic config layout THIS class writes for standalone backups
        // (uploadConfigFileToS3IfMissing / ensureConfigFilesOnFilesystem). Re-importing a
        // standalone snapshot we prepared must not read as SolrCloud.
        "dummy/zk_backup_0/configs/dummy/managed-schema.xml",
        "dummy/zk_backup_0/configs/dummy/schema.xml",
        "dummy/zk_backup/configs/dummy/managed-schema.xml"
    })
    void standaloneLayout_hasNoCloudMarkers(String path) {
        assertFalse(SolrBackupStrategy.hasCloudBackupMarker(path), path + " is a standalone backup path");
    }

    // ---- --solr-topology overrides inference ----

    @ParameterizedTest
    @ValueSource(strings = {"cloud", "Cloud", "CLOUD", " cloud "})
    void topologyOption_parsesCloud(String value) {
        assertThat(SolrBackupStrategy.parseTopologyOption(value), is(Boolean.TRUE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"standalone", "Standalone", " STANDALONE "})
    void topologyOption_parsesStandalone(String value) {
        assertThat(SolrBackupStrategy.parseTopologyOption(value), is(Boolean.FALSE));
    }

    /** Unset means "infer", not a default topology. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void topologyOption_unsetLeavesInferenceInPlace(String value) {
        assertThat(SolrBackupStrategy.parseTopologyOption(value), is((Boolean) null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cloudy", "solr", "true", "zookeeper", "solrcloud"})
    void topologyOption_rejectsUnknownValues(String value) {
        var ex = assertThrows(ParameterException.class,
            () -> SolrBackupStrategy.parseTopologyOption(value));
        assertThat(ex.getMessage(), containsString("standalone"));
    }

    // ---- Artifact inference is tri-state: absence of a marker is not evidence ----

    @ParameterizedTest
    @ValueSource(strings = {
        "dummy/snapshot.dummy-20240101/segments_1",
        "dummy/snapshot.dummy/_0.cfs",
        "dummy/snapshot.shard1backup/segments_1"
    })
    void standaloneSnapshotDir_isPositiveStandaloneEvidence(String path) {
        assertTrue(SolrBackupStrategy.hasStandaloneBackupMarker(path), path + " names a standalone backup");
    }

    /** SolrCloud's own snapshot.shardN dirs must not read as the standalone backup-name layout. */
    @ParameterizedTest
    @ValueSource(strings = {
        "movies/snapshot.shard1/segments_1",
        "movies/movies/snapshot.shard12/_0.cfs"
    })
    void cloudShardSnapshotDir_isNotStandaloneEvidence(String path) {
        assertFalse(SolrBackupStrategy.hasStandaloneBackupMarker(path), path + " is a SolrCloud shard dir");
    }

    /**
     * The tri-state gap: layouts carrying neither marker. Listing is capped at 1000 entries with
     * unspecified order, so a partially scanned backup must read as "couldn't tell", not standalone.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "dummy/zk_backup_0/configs/dummy/managed-schema.xml",
        "movies/zk_backup_0/configs/movies/managed-schema.xml",
        "movies/index/segments_1",
        "segments_1"
    })
    void neitherMarker_isEvidenceOfNeither(String path) {
        assertFalse(SolrBackupStrategy.hasCloudBackupMarker(path), path + " is not cloud evidence");
        assertFalse(SolrBackupStrategy.hasStandaloneBackupMarker(path), path + " is not standalone evidence");
    }

    // ---- Ambiguous Collections responses must not reach Core Admin ----

    /**
     * A SolrCloud node answers Core Admin STATUS with replica core names. Mining it after an
     * ambiguous Collections response would migrate those as if they were collections.
     */
    @ParameterizedTest
    @ValueSource(ints = {404, 500, 502, 503})
    void ambiguousCollectionsResponse_doesNotDiscoverCores(int status) throws Exception {
        var client = clientReturning(status, "");
        when(client.getString(anyString(), any(Duration.class))).thenReturn(CORES_BODY);

        assertThrows(SolrBackupStrategy.SolrTopologyDetectionException.class,
            () -> SolrBackupStrategy.discoverCollections(URL, client, null));
        verify(client, never()).getString(anyString(), any(Duration.class));
    }

    /** Malformed body at HTTP 200 is ambiguous too — not an invitation to read cores. */
    @Test
    void malformedCollectionsBody_doesNotDiscoverCores() throws Exception {
        var client = clientReturning(200, "<html>proxy error</html>");
        when(client.getString(anyString(), any(Duration.class))).thenReturn(CORES_BODY);

        assertThrows(SolrBackupStrategy.SolrTopologyDetectionException.class,
            () -> SolrBackupStrategy.discoverCollections(URL, client, null));
        verify(client, never()).getString(anyString(), any(Duration.class));
    }

    // ---- Status and body must agree ----

    /** A collections array inside a non-200 (cached, proxied, wrapped) is not SolrCloud answering LIST. */
    @ParameterizedTest
    @ValueSource(ints = {400, 404, 500, 502, 503})
    void collectionsArrayWithNon200Status_isUnknown(int status) throws Exception {
        assertThat(SolrBackupStrategy.detectTopology(URL, clientReturning(status, CLOUD_BODY)),
            is(SolrBackupStrategy.SolrTopology.UNKNOWN));
    }

    /** Likewise the standalone rejection: only Solr's actual 400 rejection counts. */
    @ParameterizedTest
    @ValueSource(ints = {200, 404, 500, 503})
    void standaloneMarkerWithUnexpectedStatus_isUnknown(int status) throws Exception {
        assertThat(SolrBackupStrategy.detectTopology(URL, clientReturning(status, STANDALONE_BODY)),
            is(SolrBackupStrategy.SolrTopology.UNKNOWN));
    }

    /** 'collections' must be an array, not merely present. */
    @Test
    void nonArrayCollectionsField_isUnknown() throws Exception {
        var body = "{\"responseHeader\":{\"status\":0},\"collections\":\"movies\"}";
        assertThat(SolrBackupStrategy.detectTopology(URL, clientReturning(200, body)),
            is(SolrBackupStrategy.SolrTopology.UNKNOWN));
    }

    /** An unrelated error that merely mentions SolrCloud must not read as the standalone rejection. */
    @Test
    void unrelatedErrorMentioningSolrCloud_isUnknown() throws Exception {
        var body = "{\"responseHeader\":{\"status\":400},\"error\":{"
            + "\"msg\":\"SolrCloud backup repository 'my-repo' is not configured\",\"code\":400}}";
        assertThat(SolrBackupStrategy.detectTopology(URL, clientReturning(400, body)),
            is(SolrBackupStrategy.SolrTopology.UNKNOWN));
    }
}
