package org.opensearch.migrations.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.opensearch.migrations.MigrateOrEvaluateArgs;
import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.UnboundVersionMatchers;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.transformers.FanOutCompositeTransformer;
import org.opensearch.migrations.bulkload.transformers.Transformer;
import org.opensearch.migrations.bulkload.transformers.TransformerMapper;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared functionality between migration and evaluation commands */
@Slf4j
public abstract class MigratorEvaluatorBase {
    // Log appender name is in from the MetadataMigration/src/main/resources/log4j2.properties
    public static final String TRANSFORM_LOGGER_NAME = "TransformerRun";
    private static final Logger TRANSFORM_LOGGER = LoggerFactory.getLogger(TRANSFORM_LOGGER_NAME);

    public static final String NOOP_TRANSFORMATION_CONFIG = "[" +
            "  {" +
            "    \"NoopTransformerProvider\":\"\"" +
            "  }" +
            "]";

    public static final String STRING_TEXT_KEYWORD_TRANSFORMATION_FILE = "js/es-string-test-keyword-metadata.js";
    public static final String DENSE_VECTOR_TEXT_KEYWORD_TRANSFORMATION_FILE = "js/es8-vector-metadata.js";

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

    protected Transformer getCustomTransformer(Version sourceVersion) {
        var transformerConfig = TransformerConfigUtils.getTransformerConfig(arguments.metadataCustomTransformationParams);
        if (transformerConfig != null) {
            log.atInfo().setMessage("Metadata Transformations config string: {}")
                    .addArgument(transformerConfig).log();
        } else {
            transformerConfig = getCustomTranformationConfigBySourceVersion(sourceVersion);
            log.atInfo().setMessage("Using version specific custom transformation config: {}")
                    .addArgument(sourceVersion).log();
        }
        try {
            var mapper = new ObjectMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT);
            var jsonNode = mapper.readTree(transformerConfig);
            var formattedTransformConfig = mapper.writeValueAsString(jsonNode);
            TRANSFORM_LOGGER.atInfo().setMessage("{}").addArgument(formattedTransformConfig).log();
        } catch (Exception e) {
            TRANSFORM_LOGGER.atError().setMessage("Unable to format transform config").setCause(e).log();
        }
        var transformer =  new TransformationLoader().getTransformerFactoryLoader(transformerConfig);
        return new TransformerToIJsonTransformerAdapter(transformer);
    }


    protected String getCustomTranformationConfigBySourceVersion(Version sourceVersion) {
        List<String> jsTransformationFiles = new ArrayList<>();
        if (UnboundVersionMatchers.isBelowES_6_X.test(sourceVersion)) {
            // ES 1-5 can have indexes with `string` type
            jsTransformationFiles.add(STRING_TEXT_KEYWORD_TRANSFORMATION_FILE);
        }
        if (UnboundVersionMatchers.isGreaterOrEqualES_7_X.test(sourceVersion)) {
            // dense_vector introduced in ES 7.0
            jsTransformationFiles.add(DENSE_VECTOR_TEXT_KEYWORD_TRANSFORMATION_FILE);
        }

        if (jsTransformationFiles.isEmpty()) {
            return NOOP_TRANSFORMATION_CONFIG;
        }

        return jsTransformationFiles.stream()
                .map(path ->
                        "{" +
                        "  \"JsonJSTransformerProvider\":{" +
                        "    \"initializationResourcePath\":\"" + path + "\"," +
                        "    \"bindingsObject\":\"{}\"" +
                        "  }" +
                        "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    protected Transformer selectTransformer(Clusters clusters, int awarenessAttributes, boolean allowLooseVersionMatches) {
        var mapper = new TransformerMapper(clusters.getSource().getVersion(), clusters.getTarget().getVersion());
        var versionTransformer = mapper.getTransformer(
                awarenessAttributes,
                arguments.metadataTransformationParams,
                allowLooseVersionMatches
        );
        var customTransformer = getCustomTransformer(clusters.getSource().getVersion());
        var compositeTransformer = new FanOutCompositeTransformer(customTransformer, versionTransformer);
        log.atInfo().setMessage("Selected transformer composite: custom = {}, version = {}")
            .addArgument(customTransformer.getClass().getSimpleName())
            .addArgument(versionTransformer.getClass().getSimpleName())
            .log();
        return compositeTransformer;
    }

    protected Transformer selectTransformer(Clusters clusters) {
        return selectTransformer(clusters, arguments.clusterAwarenessAttributes, arguments.versionStrictness.allowLooseVersionMatches);
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
