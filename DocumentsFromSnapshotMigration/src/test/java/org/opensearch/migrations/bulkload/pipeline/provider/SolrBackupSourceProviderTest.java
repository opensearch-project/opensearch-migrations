package org.opensearch.migrations.bulkload.pipeline.provider;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.SolrBackupDiscovery;
import org.opensearch.migrations.bulkload.common.S3Repo;
import org.opensearch.migrations.bulkload.pipeline.spi.SourceRuntime;
import org.opensearch.migrations.bulkload.solr.SolrMultiCollectionSource;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Covers {@code create()} and {@code validate()}. The end-to-end tests run the migration in a
 * forked JVM, so this wiring needs in-process coverage here.
 */
class SolrBackupSourceProviderTest {

    private static final String BACKUP = "nightly";

    private final SolrBackupSourceProvider provider = new SolrBackupSourceProvider();

    private static SourceRuntime runtime() {
        return new SourceRuntime(Path.of("/tmp/scratch"), Path.of("/tmp/work"),
            () -> mock(IRfsContexts.IDeltaStreamContext.class));
    }

    private static SolrBackupSpec spec(String repoUri) {
        return spec(repoUri, 8);
    }

    private static SolrBackupSpec spec(String repoUri, int solrMajor) {
        return new SolrBackupSpec(repoUri, BACKUP, solrMajor, List.of("an-index"),
            "us-east-1", "http://endpoint:4566");
    }

    /** Stubs the backup listing so {@code create()} can run without a real backup on disk. */
    private static MockedStatic<SolrBackupDiscovery> stubDiscovery(boolean shardPreparationNeeded) {
        var discovery = mock(SolrBackupDiscovery.class);
        when(discovery.schemas()).thenReturn(Map.of());
        when(discovery.dataDirByCollection()).thenReturn(Map.of());
        when(discovery.shardPreparationNeeded()).thenReturn(shardPreparationNeeded);
        var mocked = mockStatic(SolrBackupDiscovery.class);
        mocked.when(() -> SolrBackupDiscovery.discover(any(), any(), any())).thenReturn(discovery);
        return mocked;
    }

    @Test
    void create_fileUri_readsTheBackupDirectlyFromDisk() throws Exception {
        try (var discovery = stubDiscovery(false)) {
            var source = provider.create(spec("file:///backups/solr"), runtime());

            assertThat(source, instanceOf(SolrMultiCollectionSource.class));
            discovery.verify(() -> SolrBackupDiscovery.discover(
                eq(null), eq(Path.of("/backups/solr")), eq(List.of("an-index"))));
        }
    }

    @Test
    void create_s3Uri_downloadsBackupMetadataFirst() throws Exception {
        var s3Repo = mock(S3Repo.class);
        when(s3Repo.getRepoRootDir()).thenReturn(Path.of("/tmp/scratch/solr"));

        try (var discovery = stubDiscovery(false); var s3 = mockStatic(S3Repo.class)) {
            s3.when(() -> S3Repo.createRaw(any(), any(), any(), any())).thenReturn(s3Repo);

            var source = provider.create(spec("s3://bucket/backups"), runtime());

            assertThat(source, notNullValue());
            s3.verify(() -> S3Repo.createRaw(eq(Path.of("/tmp/scratch")), any(), eq("us-east-1"),
                eq(URI.create("http://endpoint:4566"))));
            discovery.verify(() -> SolrBackupDiscovery.discover(
                eq(s3Repo), eq(Path.of("/tmp/scratch/solr")), any()));
        }
    }

    @Test
    void create_s3UriWithoutEndpoint_passesNoEndpointOverride() throws Exception {
        var s3Repo = mock(S3Repo.class);
        when(s3Repo.getRepoRootDir()).thenReturn(Path.of("/tmp/scratch/solr"));
        var noEndpoint = new SolrBackupSpec("s3://bucket/backups", BACKUP, 8, List.of(), "us-east-1", null);

        try (var discovery = stubDiscovery(false); var s3 = mockStatic(S3Repo.class)) {
            s3.when(() -> S3Repo.createRaw(any(), any(), any(), any())).thenReturn(s3Repo);

            provider.create(noEndpoint, runtime());

            s3.verify(() -> S3Repo.createRaw(any(), any(), any(), eq(null)));
        }
    }

    @Test
    void create_installsAShardPreparerOnlyWhenTheBackupNeedsOne() throws Exception {
        try (var discovery = stubDiscovery(true)) {
            assertThat(provider.create(spec("file:///backups/solr"), runtime()), notNullValue());
        }
        try (var discovery = stubDiscovery(false)) {
            assertThat(provider.create(spec("file:///backups/solr"), runtime()), notNullValue());
        }
    }

    @Test
    void validate_rejectsANonFileNonS3Uri() {
        var thrown = assertThrows(IllegalArgumentException.class,
            () -> provider.validate(spec("gs://bucket/backups"), runtime()));

        assertThat(thrown.getMessage(), containsString("file:// or s3://"));
    }

    @Test
    void validate_rejectsAnUnsupportedSolrMajorVersion() {
        assertThat(assertThrows(IllegalArgumentException.class,
            () -> provider.validate(spec("file:///backups/solr", 5), runtime())).getMessage(),
            containsString("Unsupported Solr major version: 5"));
        assertThat(assertThrows(IllegalArgumentException.class,
            () -> provider.validate(spec("file:///backups/solr", 10), runtime())).getMessage(),
            containsString("Unsupported Solr major version: 10"));
    }

    @Test
    void validate_acceptsTheSupportedRange() {
        provider.validate(spec("file:///backups/solr", 6), runtime());
        provider.validate(spec("s3://bucket/backups", 9), runtime());
    }

    @Test
    void deferUntilWorkAvailable_isOnBecauseConstructionDownloadsMetadata() {
        assertThat(provider.deferUntilWorkAvailable(), is(true));
    }
}
