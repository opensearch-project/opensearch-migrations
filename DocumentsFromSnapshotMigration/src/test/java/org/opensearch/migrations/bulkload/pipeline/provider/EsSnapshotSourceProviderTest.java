package org.opensearch.migrations.bulkload.pipeline.provider;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.SnapshotExtractor;
import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.GcsRepo;
import org.opensearch.migrations.bulkload.common.S3Repo;
import org.opensearch.migrations.bulkload.common.SnapshotFileFinder;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.lucene.FieldMappingContext;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.pipeline.adapter.LuceneSnapshotSource;
import org.opensearch.migrations.bulkload.pipeline.spi.SourceRuntime;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.cluster.ClusterSnapshotReader;
import org.opensearch.migrations.cluster.SnapshotReaderRegistry;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@code create()}, which is pure wiring over static factories. The end-to-end tests
 * exercise it in a forked JVM, so it needs in-process coverage here.
 */
class EsSnapshotSourceProviderTest {

    private static final Version VERSION = Version.fromString("ES 7.10.2");
    private static final String SNAPSHOT = "nightly";

    private final EsSnapshotSourceProvider provider = new EsSnapshotSourceProvider();

    private static SourceRuntime runtime() {
        return new SourceRuntime(Path.of("/tmp/scratch"), Path.of("/tmp/work"),
            () -> mock(IRfsContexts.IDeltaStreamContext.class));
    }

    private static EsSnapshotSpec spec(String repoUri) {
        return spec(repoUri, null, null, false);
    }

    private static EsSnapshotSpec spec(String repoUri, String previousSnapshot, DeltaMode deltaMode,
                                       boolean sourceless) {
        return new EsSnapshotSpec(repoUri, SNAPSHOT, VERSION, List.of(), "us-east-1",
            "http://endpoint:9200", true, 1024L, false, true, previousSnapshot, deltaMode, sourceless);
    }

    /** Holds the static mocks {@code create()} needs, plus the builder it should drive. */
    private static final class Harness implements AutoCloseable {
        final MockedStatic<SnapshotReaderRegistry> registry = mockStatic(SnapshotReaderRegistry.class);
        final MockedStatic<SnapshotExtractor> extractor = mockStatic(SnapshotExtractor.class);
        final MockedStatic<LuceneSnapshotSource> source = mockStatic(LuceneSnapshotSource.class);
        final ClusterSnapshotReader snapshotReader = mock(ClusterSnapshotReader.class);
        final LuceneSnapshotSource.Builder builder = mock(LuceneSnapshotSource.Builder.class);

        Harness() {
            registry.when(() -> SnapshotReaderRegistry.getSnapshotFileFinder(any(), anyBoolean()))
                .thenReturn(mock(SnapshotFileFinder.class));
            registry.when(() -> SnapshotReaderRegistry.getSnapshotReader(any(), any(), anyBoolean()))
                .thenReturn(snapshotReader);
            extractor.when(() -> SnapshotExtractor.create(any(), any(), any()))
                .thenReturn(mock(SnapshotExtractor.class));
            source.when(() -> LuceneSnapshotSource.builder(any(), any(), any())).thenReturn(builder);
            when(builder.maxShardSizeBytes(anyLong())).thenReturn(builder);
            when(builder.useRecoverySource(anyBoolean())).thenReturn(builder);
            when(builder.emitDocType(anyBoolean())).thenReturn(builder);
            when(builder.build()).thenReturn(mock(LuceneSnapshotSource.class));
        }

        /** The SourceRepo create() built, captured from the registry lookup. */
        SourceRepo capturedRepo() {
            var captor = ArgumentCaptor.forClass(SourceRepo.class);
            registry.verify(() -> SnapshotReaderRegistry.getSnapshotReader(any(), captor.capture(), anyBoolean()));
            return captor.getValue();
        }

        @Override
        public void close() {
            source.close();
            extractor.close();
            registry.close();
        }
    }

    @Test
    void create_fileUri_buildsAFileSystemRepo() throws Exception {
        try (var h = new Harness()) {
            provider.create(spec("file:///snapshots"), runtime());

            assertThat(h.capturedRepo(), instanceOf(FileSystemRepo.class));
            verify(h.builder).maxShardSizeBytes(1024L);
            verify(h.builder).emitDocType(true);
            verify(h.builder).build();
        }
    }

    @Test
    void create_s3Uri_buildsAnS3Repo() throws Exception {
        try (var h = new Harness(); var s3 = mockStatic(S3Repo.class)) {
            var repo = mock(S3Repo.class);
            s3.when(() -> S3Repo.create(any(), any(), any(), any(), any())).thenReturn(repo);

            provider.create(spec("s3://bucket/snapshots"), runtime());

            s3.verify(() -> S3Repo.create(eq(Path.of("/tmp/scratch")), any(), eq("us-east-1"),
                eq(URI.create("http://endpoint:9200")), any()));
        }
    }

    @Test
    void create_gcsUri_buildsAGcsRepo() throws Exception {
        try (var h = new Harness(); var gcs = mockStatic(GcsRepo.class)) {
            gcs.when(() -> GcsRepo.create(any(), any(), any(), any())).thenReturn(mock(GcsRepo.class));

            provider.create(spec("gs://bucket/snapshots"), runtime());

            gcs.verify(() -> GcsRepo.create(eq(Path.of("/tmp/scratch")), any(),
                eq("http://endpoint:9200"), any()));
        }
    }

    @Test
    void create_wiresDeltaOnlyWhenBothDeltaInputsAreSet() throws Exception {
        try (var h = new Harness()) {
            provider.create(spec("file:///snapshots", "previous", DeltaMode.UPDATES_ONLY, false), runtime());

            verify(h.builder).delta(eq("previous"), eq(DeltaMode.UPDATES_ONLY), any());
        }
        try (var h = new Harness()) {
            provider.create(spec("file:///snapshots"), runtime());

            verify(h.builder, never()).delta(any(), any(), any());
        }
    }

    @Test
    void create_installsSourcelessProviderOnlyWhenEnabled() throws Exception {
        try (var h = new Harness()) {
            provider.create(spec("file:///snapshots"), runtime());

            verify(h.builder, never()).sourcelessMappingContextProvider(any());
        }
    }

    @Test
    void sourcelessProvider_returnsNullWhenReconstructionIsNotNeeded() throws Exception {
        try (var h = new Harness()) {
            var metadata = mock(IndexMetadata.class);
            when(metadata.needsSourceReconstruction()).thenReturn(false);
            stubMetadataLookup(h, metadata);

            provider.create(spec("file:///snapshots", null, null, true), runtime());

            assertThat(captureSourcelessProvider(h).apply("an-index"), is(nullValue()));
        }
    }

    @Test
    void sourcelessProvider_returnsAMappingContextWhenReconstructionIsNeeded() throws Exception {
        try (var h = new Harness()) {
            var metadata = mock(IndexMetadata.class);
            when(metadata.needsSourceReconstruction()).thenReturn(true);
            when(metadata.getMappings()).thenReturn(JsonNodeFactory.instance.objectNode());
            stubMetadataLookup(h, metadata);

            provider.create(spec("file:///snapshots", null, null, true), runtime());

            assertThat(captureSourcelessProvider(h).apply("an-index"), instanceOf(FieldMappingContext.class));
        }
    }

    @Test
    void sourcelessProvider_wrapsAMetadataReadFailure() throws Exception {
        try (var h = new Harness()) {
            var factory = mock(IndexMetadata.Factory.class);
            when(factory.fromRepo(any(), any())).thenThrow(new RuntimeException("repo is unreadable"));
            when(h.snapshotReader.getIndexMetadata()).thenReturn(factory);

            provider.create(spec("file:///snapshots", null, null, true), runtime());
            var sourceless = captureSourcelessProvider(h);

            var thrown = assertThrows(IllegalStateException.class, () -> sourceless.apply("an-index"));
            assertThat(thrown.getMessage(), containsString("an-index"));
        }
    }

    private static void stubMetadataLookup(Harness h, IndexMetadata metadata) throws Exception {
        var factory = mock(IndexMetadata.Factory.class);
        when(factory.fromRepo(SNAPSHOT, "an-index")).thenReturn(metadata);
        when(h.snapshotReader.getIndexMetadata()).thenReturn(factory);
    }

    @SuppressWarnings("unchecked")
    private static Function<String, FieldMappingContext> captureSourcelessProvider(Harness h) {
        var captor = ArgumentCaptor.forClass(Function.class);
        verify(h.builder).sourcelessMappingContextProvider(captor.capture());
        return captor.getValue();
    }
}
