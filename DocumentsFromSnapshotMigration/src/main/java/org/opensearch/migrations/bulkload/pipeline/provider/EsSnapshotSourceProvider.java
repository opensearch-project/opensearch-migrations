package org.opensearch.migrations.bulkload.pipeline.provider;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.opensearch.migrations.bulkload.SnapshotExtractor;
import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.GcsRepo;
import org.opensearch.migrations.bulkload.common.GcsUri;
import org.opensearch.migrations.bulkload.common.RepoUri;
import org.opensearch.migrations.bulkload.common.S3Repo;
import org.opensearch.migrations.bulkload.common.SourceRepo;
import org.opensearch.migrations.bulkload.lucene.FieldMappingContext;
import org.opensearch.migrations.bulkload.pipeline.adapter.LuceneSnapshotSource;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.pipeline.spi.DocumentSourceProvider;
import org.opensearch.migrations.bulkload.pipeline.spi.SourceRuntime;
import org.opensearch.migrations.cluster.SnapshotReaderRegistry;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Serves documents from an Elasticsearch/OpenSearch snapshot repository.
 *
 * <p>Picks the snapshot file finder for the source version, opens the {@link SourceRepo} matching
 * the repo URI scheme, and wires the optional delta and sourceless reads.
 */
public class EsSnapshotSourceProvider implements DocumentSourceProvider<EsSnapshotSourceSpec> {

    public static final String KIND = "es-snapshot";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public EsSnapshotSourceSpec parseSpec(JsonNode config) {
        return EsSnapshotSourceSpec.fromJson(config);
    }

    @Override
    public void validate(EsSnapshotSourceSpec spec, SourceRuntime runtime) {
        if (spec.previousSnapshotName() != null && spec.deltaMode() == null) {
            throw new IllegalArgumentException(
                "A previous snapshot was given without a delta mode; a delta read needs both.");
        }
        if (spec.deltaMode() != null && spec.previousSnapshotName() == null) {
            throw new IllegalArgumentException(
                "A delta mode was given without a previous snapshot; a delta read needs both.");
        }
    }

    @Override
    public DocumentSource create(EsSnapshotSourceSpec spec, SourceRuntime runtime) throws IOException {
        var finder = SnapshotReaderRegistry.getSnapshotFileFinder(
            spec.version(), spec.allowLooseVersionMatches());

        SourceRepo sourceRepo = switch (RepoUri.parse(spec.repoUri())) {
            case RepoUri.FileRepoUri f -> new FileSystemRepo(Paths.get(f.path()), finder);
            case RepoUri.GcsRepoUri g -> GcsRepo.create(
                runtime.scratchDir(), new GcsUri(g.rawUri()), spec.endpoint(), finder);
            case RepoUri.S3RepoUri s -> S3Repo.create(
                runtime.scratchDir(),
                s.s3Uri(),
                spec.s3Region(),
                Optional.ofNullable(spec.endpoint()).map(URI::create).orElse(null),
                finder);
        };

        var snapshotReader = SnapshotReaderRegistry.getSnapshotReader(
            spec.version(), sourceRepo, spec.allowLooseVersionMatches());
        var extractor = SnapshotExtractor.create(spec.version(), snapshotReader, sourceRepo);

        var builder = LuceneSnapshotSource.builder(extractor, spec.snapshotName(), runtime.workDir())
            .maxShardSizeBytes(spec.maxShardSizeBytes())
            .useRecoverySource(spec.useRecoverySource())
            .emitDocType(spec.emitDocType());

        if (spec.isDeltaRead()) {
            builder.delta(spec.previousSnapshotName(), spec.deltaMode(), runtime.deltaStreamContextFactory());
        }

        if (spec.enableSourcelessMigrations()) {
            var indexMetadataFactory = snapshotReader.getIndexMetadata();
            Map<String, Optional<FieldMappingContext>> cache = new ConcurrentHashMap<>();
            builder.sourcelessMappingContextProvider(indexName -> cache.computeIfAbsent(indexName, name -> {
                try {
                    var meta = indexMetadataFactory.fromRepo(spec.snapshotName(), name);
                    if (!meta.needsSourceReconstruction()) {
                        return Optional.empty();
                    }
                    return Optional.of(new FieldMappingContext(meta.getMappings()));
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to read metadata for index " + name, e);
                }
            }).orElse(null));
        }

        return builder.build();
    }
}
