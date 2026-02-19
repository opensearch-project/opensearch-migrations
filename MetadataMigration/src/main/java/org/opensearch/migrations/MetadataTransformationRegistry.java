package org.opensearch.migrations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.transformers.Transformer;
import org.opensearch.migrations.bulkload.transformers.TransformerToIJsonTransformerAdapter;
import org.opensearch.migrations.cli.Transformers;
import org.opensearch.migrations.transform.TransformationLoader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Slf4j
@UtilityClass
public class MetadataTransformationRegistry {
    // Log appender name is in from the MetadataMigration/src/main/resources/log4j2.properties
    public static final String TRANSFORM_LOGGER_NAME = "TransformerRun";
    private static final Logger TRANSFORM_LOGGER = LoggerFactory.getLogger(TRANSFORM_LOGGER_NAME);

    public static final String NOOP_TRANSFORMATION_CONFIG = "[" +
        "  {" +
        "    \"NoopTransformerProvider\":\"\"" +
        "  }" +
        "]";

    private static final List<TransformerConfigs> BAKED_IN_TRANSFORMER_CONFIGS = List.of(
        TransformerConfigs.builder()
            .filename("js/es-string-text-keyword-metadata.js")
            .isRelevantForVersions(andSourceTargetVersionPredicate(
                UnboundVersionMatchers.isBelowES_6_X,
                UnboundVersionMatchers.isBelowES_5_X.negate()
            ))
            .transformerInfo(Transformers.TransformerInfo.builder()
                .name("Field Data Type Deprecation - string")
                .descriptionLine("Convert field data type string to text/keyword")
                .build())
            .build(),
        TransformerConfigs.builder()
            .filename("js/es-vector-knn-metadata.js")
            .isRelevantForVersions(andSourceTargetVersionPredicate(
                    UnboundVersionMatchers.isGreaterOrEqualES_7_X,
                    UnboundVersionMatchers.anyOS
            ))
            .transformerInfo(Transformers.TransformerInfo.builder()
                .name("dense_vector to knn_vector")
                .descriptionLine("Convert field data type dense_vector to OpenSearch knn_vector")
                .build())
            .build(),
        TransformerConfigs.builder()
            .filename("js/es-semantic-text-metadata.js")
            .isRelevantForVersions(andSourceTargetVersionPredicate(
                    UnboundVersionMatchers.isGreaterOrEqualES_8_X,
                    UnboundVersionMatchers.isGreaterOrEqualOS_3_x
            ))
            .transformerInfo(Transformers.TransformerInfo.builder()
                .name("semantic_text to semantic")
                .descriptionLine("Convert field data type semantic_text to OpenSearch semantic")
                .build())
            .build(),
        TransformerConfigs.builder()
            .filename("js/knn-to-serverless-metadata.js")
            .isRelevantForVersions(andSourceTargetVersionPredicate(
                    v -> true,
                    UnboundVersionMatchers.isAmazonServerlessOpenSearch
            ))
            .transformerInfo(Transformers.TransformerInfo.builder()
                .name("knn_vector to Serverless-compatible Faiss HNSW")
                .descriptionLine("Convert knn_vector fields to Faiss HNSW for OpenSearch Serverless compatibility")
                .build())
            .build(),
        TransformerConfigs.builder()
            .filename("js/knn-nmslib-to-faiss-metadata.js")
            .isRelevantForVersions(andSourceTargetVersionPredicate(
                    UnboundVersionMatchers.anyOS.and(UnboundVersionMatchers.isGreaterOrEqualOS_3_x.negate()),
                    UnboundVersionMatchers.isGreaterOrEqualOS_3_x
            ))
            .transformerInfo(Transformers.TransformerInfo.builder()
                .name("nmslib to faiss engine")
                .descriptionLine("Convert nmslib knn_vector engine to faiss (nmslib deprecated in OS 3.0)")
                .build())
            .build(),
        TransformerConfigs.builder()
            .filename("js/metadataUpdater.js")
            .context(
                "{" +
                "  \"rules\": [" +
                "    {" +
                "      \"when\": { \"type\": \"flattened\" }," +
                "      \"set\": { \"type\": \"flat_object\" }," +
                "      \"remove\": [\"index\"]" +
                "    }" +
                "  ]" +
                "}")
            .isRelevantForVersions(andSourceTargetVersionPredicate(
                UnboundVersionMatchers.isGreaterOrEqualES_7_3,
                UnboundVersionMatchers.equalOrGreaterThanOS_2_7
            ))
            .transformerInfo(Transformers.TransformerInfo.builder()
                .name("flattened to flat_object")
                .descriptionLine("Convert field data type flattened to OpenSearch flat_object")
                .build())
            .build()
    );

    private static BiPredicate<Version, Version> andSourceTargetVersionPredicate(
        Predicate<Version> sourcePredicate,
        Predicate<Version> targetPredicate
    ) {
        return (source, target) -> sourcePredicate.test(source) && targetPredicate.test(target);
    }

    @Getter
    @Builder
    private static class TransformerConfigs {
        @NonNull private Transformers.TransformerInfo transformerInfo;
        @NonNull private String filename;
        private String context;
        @NonNull private BiPredicate<Version, Version> isRelevantForVersions;
    }

    public static Transformers getCustomTransformationByClusterVersions(Version sourceVersion, Version targetVersion) {
        return getCustomTransformationByClusterVersions(sourceVersion, targetVersion, Collections.emptyMap());
    }

    public static Transformers getCustomTransformationByClusterVersions(
            Version sourceVersion, Version targetVersion, Map<String, String> modelMappings) {
        var transformersBuilder = Transformers.builder();
        var bakedInTransformers = getBakedInConfigs(modelMappings)
            .stream().filter(config ->
                config.isRelevantForVersions.test(sourceVersion, targetVersion))
            .toList();
        transformersBuilder.transformerInfos(bakedInTransformers.stream().map(TransformerConfigs::getTransformerInfo).collect(Collectors.toList()));
        var config = getAggregateJSTransformer(bakedInTransformers);
        logTransformerConfig("Default breaking changes transform config", config);
        transformersBuilder.transformer(configToTransformer(config));
        return transformersBuilder.build();
    }

    /**
     * Returns the baked-in transformer configs, injecting model_mappings into the
     * semantic text transform context if provided.
     */
    private static List<TransformerConfigs> getBakedInConfigs(Map<String, String> modelMappings) {
        if (modelMappings == null || modelMappings.isEmpty()) {
            return BAKED_IN_TRANSFORMER_CONFIGS;
        }
        // Replace the semantic text config with one that includes model_mappings
        var configs = new ArrayList<TransformerConfigs>();
        for (var config : BAKED_IN_TRANSFORMER_CONFIGS) {
            if (config.getFilename().contains("es-semantic-text-metadata")) {
                configs.add(TransformerConfigs.builder()
                    .filename(config.getFilename())
                    .context(buildModelMappingsContext(modelMappings))
                    .isRelevantForVersions(config.getIsRelevantForVersions())
                    .transformerInfo(config.getTransformerInfo())
                    .build());
            } else {
                configs.add(config);
            }
        }
        return configs;
    }

    private static String buildModelMappingsContext(Map<String, String> modelMappings) {
        var entries = modelMappings.entrySet().stream()
            .map(e -> "\"" + escapeJson(e.getKey()) + "\":\"" + escapeJson(e.getValue()) + "\"")
            .collect(Collectors.joining(","));
        return "{\"model_mappings\":{" + entries + "}}";
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String getAggregateJSTransformer(List<TransformerConfigs> transformerConfigs) {
        return transformerConfigs.isEmpty() ? NOOP_TRANSFORMATION_CONFIG :
            transformerConfigs.stream()
                .map(config -> getJSTransform(config.getFilename(), config.getContext()))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String getJSTransform(String filename, String context) {
        var bindings = context == null ? "{}" : context.replace("\"", "\\\"");
        return  "{" +
            "  \"JsonJSTransformerProvider\":{" +
            "    \"initializationResourcePath\":\"" + filename + "\"," +
            "    \"bindingsObject\": \"" + bindings + "\"" +
            "  }" +
            "}";
    }

    public static Transformer configToTransformer(String config) {
        var transformer =  new TransformationLoader().getTransformerFactoryLoader(config);
        return new TransformerToIJsonTransformerAdapter(transformer);
    }

    public static void logTransformerConfig(String title, String transformerConfig) {
        log.atInfo().setMessage("{}:\n{}")
            .addArgument(title)
            .addArgument(transformerConfig).log();
        try {
            var mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
            var jsonNode = mapper.readTree(transformerConfig);
            var formattedTransformConfig = mapper.writeValueAsString(jsonNode);
            TRANSFORM_LOGGER.atInfo().setMessage("{}\n{}")
                .addArgument(title)
                .addArgument(formattedTransformConfig).log();
        } catch (Exception e) {
            TRANSFORM_LOGGER.atError().setMessage("Unable to format transform config").setCause(e).log();
        }
    }
}
