package org.opensearch.migrations.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.opensearch.migrations.MetadataTransformationRegistry;
import org.opensearch.migrations.MigrateOrEvaluateArgs;
import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.Version;
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
import org.opensearch.migrations.cluster.ClusterProviderRegistry;
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

        var targetCluster = ClusterProviderRegistry.getRemoteWriter(arguments.targetArgs.toConnectionContext(), null, arguments.dataFilterArgs, arguments.versionStrictness.allowLooseVersionMatches);
        clusters.target(targetCluster);
        return clusters.build();
    }

    protected Transformers getCustomTransformer(Version sourceVersion, Version targetVersion) {
        return getCustomTransformer(sourceVersion, targetVersion, Collections.emptyMap());
    }

    protected Transformers getCustomTransformer(Version sourceVersion, Version targetVersion, Map<String, String> modelMappings) {
        var versionSpecificCustomTransforms = MetadataTransformationRegistry.getCustomTransformationByClusterVersions(
            sourceVersion, targetVersion, modelMappings);
        var transformerConfig = TransformerConfigUtils.getTransformerConfig(arguments.metadataCustomTransformationParams);
        if (transformerConfig != null) {
            MetadataTransformationRegistry.logTransformerConfig("User supplied custom transform", transformerConfig);
            var customTransformInfoBuilder = Transformers.TransformerInfo
                    .builder()
                    .name("User Supplied Custom Transform")
                    .descriptionLine("Custom transformation applied from supplied arguments.");
                if (!versionSpecificCustomTransforms.getTransformerInfos().isEmpty()) {
                    customTransformInfoBuilder
                        .descriptionLine("Skipped default version-specific transformations:")
                        .descriptionLine("(" + versionSpecificCustomTransforms.getTransformerInfos().stream()
                            .map(Transformers.TransformerInfo::getName).collect(Collectors.joining(", ")) + ")") ;
                }
                return Transformers.builder()
                    .transformer(MetadataTransformationRegistry.configToTransformer(transformerConfig))
                    .transformerInfo(customTransformInfoBuilder.build())
                    .build();
        }
        return versionSpecificCustomTransforms;
    }

    protected Transformers selectTransformer(Clusters clusters, int awarenessAttributes, boolean allowLooseVersionMatches) {
        return selectTransformer(clusters, awarenessAttributes, allowLooseVersionMatches, Collections.emptyMap());
    }

    protected Transformers selectTransformer(Clusters clusters, int awarenessAttributes, boolean allowLooseVersionMatches, Map<String, String> modelMappings) {
        var mapper = new TransformerMapper(clusters.getSource().getVersion(), clusters.getTarget().getVersion());
        var versionTransformer = mapper.getTransformer(
                awarenessAttributes,
                arguments.metadataTransformationParams,
                allowLooseVersionMatches
        );
        var customTransformer = getCustomTransformer(clusters.getSource().getVersion(), clusters.getTarget().getVersion(), modelMappings);
        log.atInfo().setMessage("Selected transformer composite: custom = {}, version = {}")
                .addArgument(customTransformer.getClass().getSimpleName())
                .addArgument(versionTransformer.getClass().getSimpleName())
                .log();
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

    protected Transformers selectTransformer(Clusters clusters, Map<String, String> modelMappings) {
        return selectTransformer(clusters, arguments.clusterAwarenessAttributes, arguments.versionStrictness.allowLooseVersionMatches, modelMappings);
    }

    protected Items migrateAllItems(MigrationMode migrationMode, Clusters clusters, Transformer transformer, RootMetadataMigrationContext context) {
        var items = Items.builder();
        items.dryRun(migrationMode.equals(MigrationMode.SIMULATE));
        var metadataResults = migrateGlobalMetadata(migrationMode, clusters, transformer, context);

        var indexTemplates = new ArrayList<CreationResult>();
        indexTemplates.addAll(metadataResults.getLegacyTemplates());
        indexTemplates.addAll(metadataResults.getIndexTemplates());
        items.indexTemplates(indexTemplates);
        items.componentTemplates(metadataResults.getComponentTemplates());

        if (metadataResults.fatalIssueCount() == 0) {
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
}
