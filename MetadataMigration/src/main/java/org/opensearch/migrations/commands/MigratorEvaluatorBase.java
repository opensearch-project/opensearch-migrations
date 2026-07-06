package org.opensearch.migrations.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.opensearch.migrations.MetadataTransformationRegistry;
import org.opensearch.migrations.MigrateOrEvaluateArgs;
import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.FilterScheme;
import org.opensearch.migrations.bulkload.common.SnapshotReadFailures;
import org.opensearch.migrations.bulkload.transformers.FanOutCompositeTransformer;
import org.opensearch.migrations.bulkload.transformers.Transformer;
import org.opensearch.migrations.bulkload.transformers.TransformerMapper;
import org.opensearch.migrations.bulkload.worker.IndexMetadataResults;
import org.opensearch.migrations.bulkload.worker.IndexRunner;
import org.opensearch.migrations.bulkload.worker.MetadataRunner;
import org.opensearch.migrations.cli.ClusterReaderExtractor;
import org.opensearch.migrations.cli.Clusters;
import org.opensearch.migrations.cli.Items;
import org.opensearch.migrations.cli.Transformers;
import org.opensearch.migrations.cluster.ClusterWriterRegistry;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.GlobalMetadataCreatorResults;
import org.opensearch.migrations.metadata.tracing.RootMetadataMigrationContext;
import org.opensearch.migrations.transform.TransformerConfigUtils;

import lombok.extern.slf4j.Slf4j;

/** Shared functionality between migration and evaluation commands */
@Slf4j
public abstract class MigratorEvaluatorBase {

    static final int INVALID_PARAMETER_CODE = 999;
    static final int UNEXPECTED_FAILURE_CODE = 888;
    // Keep harmonized with the document worker's RfsMigrateDocuments.SNAPSHOT_READ_FAILED_EXIT_CODE.
    public static final int SNAPSHOT_READ_FAILED_EXIT_CODE = 5;

    protected final MigrateOrEvaluateArgs arguments;
    protected final ClusterReaderExtractor clusterReaderCliExtractor;

    protected MigratorEvaluatorBase(MigrateOrEvaluateArgs arguments) {
        this.arguments = arguments;
        this.clusterReaderCliExtractor = new ClusterReaderExtractor(arguments);
    }

    protected Clusters createClusters() {
        var clusters = Clusters.builder();
        var sourceCluster = clusterReaderCliExtractor.extractClusterReader();
        clusters.source(sourceCluster);

        var targetCluster = ClusterWriterRegistry.getRemoteWriter(arguments.targetArgs.toConnectionContext(), null, arguments.dataFilterArgs, arguments.versionStrictness.allowLooseVersionMatches);
        clusters.target(targetCluster);
        return clusters.build();
    }

    protected Transformers getCustomTransformer(Version sourceVersion, Version targetVersion) {
        var versionSpecificCustomTransforms = MetadataTransformationRegistry.getCustomTransformationByClusterVersions(sourceVersion, targetVersion);
        var transformerConfig = TransformerConfigUtils.getTransformerConfig(arguments.metadataCustomTransformationParams);
        if (transformerConfig != null) {
            MetadataTransformationRegistry.logTransformerConfig("User supplied custom transform", transformerConfig);
            var userTransformer = MetadataTransformationRegistry.configToTransformer(transformerConfig);
            var userTransformInfo = Transformers.TransformerInfo
                    .builder()
                    .name("User Supplied Custom Transform")
                    .descriptionLine("Custom transformation applied from supplied arguments.")
                    .build();
            // Auto-applied version-specific transforms (e.g. multi-type mapping union, analysis
            // compatibility, knn shape fixes) ALWAYS run — a user-supplied transform composes
            // alongside them rather than replacing them. The version transforms run first so the
            // user transform sees already-normalized mappings.
            return Transformers.builder()
                    .transformer(new FanOutCompositeTransformer(
                        versionSpecificCustomTransforms.getTransformer(),
                        userTransformer))
                    .transformerInfos(versionSpecificCustomTransforms.getTransformerInfos())
                    .transformerInfo(userTransformInfo)
                    .build();
        }
        return versionSpecificCustomTransforms;
    }

    protected Transformers selectTransformer(Clusters clusters, int awarenessAttributes, boolean allowLooseVersionMatches) {
        var mapper = new TransformerMapper(clusters.getSource().getVersion(), clusters.getTarget().getVersion());
        var versionTransformer = mapper.getTransformer(
                awarenessAttributes,
                allowLooseVersionMatches
        );
        var customTransformer = getCustomTransformer(clusters.getSource().getVersion(), clusters.getTarget().getVersion());
        log.atInfo().setMessage("Selected transformer composite: custom = {}, version = {}")
                .addArgument(customTransformer.getClass().getSimpleName())
                .addArgument(versionTransformer.getClass().getSimpleName())
                .log();
        // Custom transformers run BEFORE the version transformer. The auto-applied
        // TypeMappingsSanitizationTransformer (multi-type union) and user-supplied custom
        // transforms must reshape the index/mappings first so the version rules operate on
        // already-resolved single-type mappings.
        return Transformers.builder()
                .transformer(new FanOutCompositeTransformer(customTransformer.getTransformer(), versionTransformer))
                .transformerInfos(customTransformer.getTransformerInfos())
                .transformerInfo(Transformers.TransformerInfo.builder()
                    .name("Version Transform")
                    .descriptionLine("Other transforms for source to target shape conversion")
                    .build())
                .build();
    }

    protected Transformers selectTransformer(Clusters clusters) {
        return selectTransformer(clusters, arguments.clusterAwarenessAttributes, arguments.versionStrictness.allowLooseVersionMatches);
    }

    protected Items migrateAllItems(MigrationMode migrationMode, Clusters clusters, Transformer transformer, RootMetadataMigrationContext context) {
        var items = Items.builder();
        items.dryRun(migrationMode.equals(MigrationMode.SIMULATE));
        items.succeedOnEmpty(arguments.succeedOnEmpty);
        items.allowExistingIndexes(arguments.allowExistingIndexes);
        var metadataResults = migrateGlobalMetadata(migrationMode, clusters, transformer, context);

        var indexTemplates = new ArrayList<CreationResult>();
        indexTemplates.addAll(metadataResults.getLegacyTemplates());
        indexTemplates.addAll(metadataResults.getIndexTemplates());
        items.indexTemplates(indexTemplates);
        items.componentTemplates(metadataResults.getComponentTemplates());

        if (metadataResults.fatalIssueCount() == 0) {
            // Validate sourceless indices before proceeding with migration
            validateSourcelessIndices(clusters);

            var indexResults = migrateIndices(migrationMode, clusters, transformer, context);
            items.indexes(indexResults.getIndexes());
            items.aliases(indexResults.getAliases());
        } else {
            items.indexes(List.of());
            items.aliases(List.of());
            log.warn("Stopping before index migration due to issues");
            items.failureMessage("Encountered " + metadataResults.fatalIssueCount() + " fatal issue(s) while moving global objects.");
        }

        return items.build();
    }

    private GlobalMetadataCreatorResults migrateGlobalMetadata(MigrationMode mode, Clusters clusters, Transformer transformer, RootMetadataMigrationContext context) {
        var metadataRunner = new MetadataRunner(
            arguments.snapshotName,
            clusters.getSource().getGlobalMetadata(),
            clusters.getTarget().getGlobalMetadataCreator(),
            transformer
        );
        var metadataResults = metadataRunner.migrateMetadata(mode, context.createMetadataMigrationContext());
        log.info("Metadata copy complete.");
        return metadataResults;
    }

    private IndexMetadataResults migrateIndices(MigrationMode mode, Clusters clusters, Transformer transformer, RootMetadataMigrationContext context) {
        var indexRunner = new IndexRunner(
            arguments.snapshotName,
            clusters.getSource().getIndexMetadata(),
            clusters.getTarget().getIndexCreator(),
            transformer,
            arguments.dataFilterArgs.indexAllowlist,
            clusters.getTarget().getAwarenessAttributeSettings()
        );
        var indexResults = indexRunner.migrateIndices(mode, context.createIndexContext());
        log.info("Index copy complete.");
        return indexResults;
    }

    protected String createUnexpectedErrorMessage(Throwable e) {
        var causeMessage = Optional.of(e).map(Throwable::getCause).map(Throwable::getMessage).orElse(null);
        return "Unexpected failure: " + e.getMessage() + (causeMessage == null ? "" : ", inner cause: " + causeMessage);
    }

    /**
     * Classify a failure from the migrate/evaluate flow. A non-retriable snapshot read failure —
     * the snapshot's repo/global/index metadata could not be read — is mapped to the
     * dedicated {@link #SNAPSHOT_READ_FAILED_EXIT_CODE} and a labeled message naming the snapshot
     * path; anything else keeps the generic "unexpected failure" behavior. The detailed cause is
     * logged here (to the run log); the caller surfaces the summary on stdout (see
     * {@code MetadataMigration.run}) so it reaches the workflow log / CloudWatch before the process
     * exits.
     */
    protected FailureClassification classifyFailure(Exception e) {
        var readFailure = SnapshotReadFailures.find(e);
        if (readFailure != null) {
            var repo = arguments.repoUri;
            var message = SnapshotReadFailures.describe(
                readFailure, arguments.snapshotName, repo, arguments.s3Region);
            log.atError().setCause(e).setMessage("{}").addArgument(message).log();
            return new FailureClassification(SNAPSHOT_READ_FAILED_EXIT_CODE, message);
        }
        log.atError().setCause(e).setMessage("Unexpected failure").log();
        return new FailureClassification(UNEXPECTED_FAILURE_CODE, createUnexpectedErrorMessage(e));
    }

    /** Outcome of {@link #classifyFailure(Exception)}: the process exit code and user-facing message. */
    protected static class FailureClassification {
        public final int exitCode;
        public final String errorMessage;

        public FailureClassification(int exitCode, String errorMessage) {
            this.exitCode = exitCode;
            this.errorMessage = errorMessage;
        }
    }

    /**
     * Validates that no selected indices have _source disabled unless --enable-sourceless-migrations is set.
     * Throws ParameterException if sourceless indices are found without the flag.
     */
    protected void validateSourcelessIndices(Clusters clusters) {
        var metadataFactory = clusters.getSource().getIndexMetadata();
        var repoDataProvider = metadataFactory.getRepoDataProvider();
        var skipFilter = FilterScheme.filterByAllowList(arguments.dataFilterArgs.indexAllowlist, FilterScheme.FilterContext.INDEX).negate();
        var sourcelessIndices = new ArrayList<String>();

        for (var index : repoDataProvider.getIndicesInSnapshot(arguments.snapshotName)) {
            if (skipFilter.test(index.getName())) {
                continue;
            }
            try {
                var indexMetadata = metadataFactory.fromRepo(arguments.snapshotName, index.getName());
                if (indexMetadata.needsSourceReconstruction()) {
                    sourcelessIndices.add(index.getName());
                }
            } catch (Exception e) {
                log.warn("Could not check _source status for index {}: {}", index.getName(), e.getMessage());
            }
        }

        if (!sourcelessIndices.isEmpty() && !arguments.enableSourcelessMigrations) {
            throw new com.beust.jcommander.ParameterException(
                "The following indices have _source disabled or partial (includes/excludes): " + sourcelessIndices + ". "
                + "Document backfill will not be able to migrate these indices without the "
                + "--enable-sourceless-migrations flag on both metadata migration and backfill commands. "
                + "With this flag, documents will be reconstructed from stored fields and doc_values."
            );
        }

        if (!sourcelessIndices.isEmpty()) {
            log.info("Sourceless indices detected (--enable-sourceless-migrations is set): {}", sourcelessIndices);
        }
    }
}
