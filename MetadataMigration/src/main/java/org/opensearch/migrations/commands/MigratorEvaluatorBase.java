package org.opensearch.migrations.commands;

import java.util.ArrayList;
import java.util.List;

import org.opensearch.migrations.MigrateOrEvaluateArgs;
import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.transformers.CompositeTransformer;
import org.opensearch.migrations.bulkload.transformers.TransformFunctions;
import org.opensearch.migrations.bulkload.transformers.Transformer;
import org.opensearch.migrations.bulkload.transformers.TransformerToIJsonTransformerAdapter;
import org.opensearch.migrations.bulkload.worker.IndexMetadataResults;
import org.opensearch.migrations.bulkload.worker.IndexRunner;
import org.opensearch.migrations.bulkload.worker.MetadataRunner;
import org.opensearch.migrations.cli.ClusterReaderExtractor;
import org.opensearch.migrations.cli.Clusters;
import org.opensearch.migrations.cli.Items;
import org.opensearch.migrations.cluster.ClusterProviderRegistry;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.GlobalMetadataCreatorResults;
import org.opensearch.migrations.metadata.tracing.RootMetadataMigrationContext;
import org.opensearch.migrations.transform.TransformationLoader;
import org.opensearch.migrations.transform.TransformerConfigUtils;

import lombok.extern.slf4j.Slf4j;

/** Shared functionality between migration and evaluation commands */
@Slf4j
public abstract class MigratorEvaluatorBase {
    public static final String NOOP_TRANSFORMATION_CONFIG = "[" +
            "  {" +
            "    \"NoopTransformerProvider\":\"\"" +
            "  }" +
            "]";

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

        var targetCluster = ClusterProviderRegistry.getRemoteWriter(arguments.targetArgs.toConnectionContext(), null, arguments.dataFilterArgs);
        clusters.target(targetCluster);
        return clusters.build();
    }

    protected Transformer getCustomTransformer() {
        var transformerConfig = TransformerConfigUtils.getTransformerConfig(arguments.metadataTransformationParams);
        if (transformerConfig != null) {
            log.atInfo().setMessage("Metadata Transformations config string: {}")
                    .addArgument(transformerConfig).log();
        } else {
            log.atInfo().setMessage("Using Noop custom transformation config: {}")
                    .addArgument(NOOP_TRANSFORMATION_CONFIG).log();
            transformerConfig = NOOP_TRANSFORMATION_CONFIG;
        }
        var transformer =  new TransformationLoader().getTransformerFactoryLoader(transformerConfig);
        return new TransformerToIJsonTransformerAdapter(transformer);
    }

    protected Transformer selectTransformer(Clusters clusters) {
        var versionTransformer = TransformFunctions.getTransformer(
            clusters.getSource().getVersion(),
            clusters.getTarget().getVersion(),
            arguments.minNumberOfReplicas
        );
        var customTransformer = getCustomTransformer();
        var compositeTransformer = new CompositeTransformer(customTransformer, versionTransformer);
        log.atInfo().setMessage("Selected transformer: {}").addArgument(compositeTransformer).log();
        return compositeTransformer;
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
            arguments.dataFilterArgs.indexAllowlist
        );
        var indexResults = indexRunner.migrateIndices(mode, context.createIndexContext());
        log.info("Index copy complete.");
        return indexResults;
    } 
}
