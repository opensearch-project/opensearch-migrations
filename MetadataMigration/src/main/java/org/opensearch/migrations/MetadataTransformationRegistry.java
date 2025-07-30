package org.opensearch.migrations;

import java.util.List;
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
            .isRelevantForSourceVersion(UnboundVersionMatchers.isBelowES_6_X)
            .transformerInfo(Transformers.TransformerInfo.builder()
                .name("Field Data Type Deprecation - string")
                .descriptionLine("Convert mapping type string to text/keyword based on field data mappings")
                .build())
            .build(),
        TransformerConfigs.builder()
            .filename("js/es-vector-knn-metadata.js")
            .isRelevantForSourceVersion(UnboundVersionMatchers.isGreaterOrEqualES_7_X)
            .transformerInfo(Transformers.TransformerInfo.builder()
                .name("dense_vector to knn_vector")
                .descriptionLine("Convert mapping type dense_vector to OpenSearch knn_vector")
                .build())
            .build()
    );


    @Getter
    @Builder
    private static class TransformerConfigs {
        @NonNull private Transformers.TransformerInfo transformerInfo;
        @NonNull private String filename;
        @NonNull private Predicate<Version> isRelevantForSourceVersion;
    }

    public static Transformers getCustomTransformationBySourceVersion(Version sourceVersion) {
        var transformersBuilder = Transformers.builder();
        var bakedInTransformers = BAKED_IN_TRANSFORMER_CONFIGS
            .stream().filter(config ->
                config.getIsRelevantForSourceVersion().test(sourceVersion))
            .toList();
        transformersBuilder.transformerInfos(bakedInTransformers.stream().map(TransformerConfigs::getTransformerInfo).collect(Collectors.toList()));
        var config = getAggregateJSTransformer(bakedInTransformers.stream().map(TransformerConfigs::getFilename).toList());
        logTransformerConfig("Default breaking changes transform config", config);
        transformersBuilder.transformer(configToTransformer(config));
        return transformersBuilder.build();
    }

    private static String getAggregateJSTransformer(List<String> jsFilenames) {
        return jsFilenames.isEmpty() ? NOOP_TRANSFORMATION_CONFIG :
            jsFilenames.stream()
                .map(filename ->
                    "{" +
                        "  \"JsonJSTransformerProvider\":{" +
                        "    \"initializationResourcePath\":\"" + filename + "\"," +
                        "    \"bindingsObject\":\"{}\"" +
                        "  }" +
                        "}")
                .collect(Collectors.joining(",", "[", "]"));
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
