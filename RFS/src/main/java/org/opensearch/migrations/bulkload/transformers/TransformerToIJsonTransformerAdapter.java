package org.opensearch.migrations.bulkload.transformers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.models.ComponentTemplate;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;
import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.models.IndexTemplate;
import org.opensearch.migrations.bulkload.models.LegacyTemplate;
import org.opensearch.migrations.bulkload.models.MigrationItem;
import org.opensearch.migrations.transform.IJsonTransformer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapts an {@link IJsonTransformer} to a {@link Transformer}.
 */
@Slf4j
public class TransformerToIJsonTransformerAdapter implements Transformer {
    public static final String OUTPUT_TRANSFORMATION_JSON_LOGGER = "OutputTransformationJsonLogger";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final IJsonTransformer transformer;
    private final Logger transformerLogger;

    public TransformerToIJsonTransformerAdapter(IJsonTransformer transformer, Logger transformerLogger) {
        this.transformer = transformer;
        this.transformerLogger = transformerLogger != null ? transformerLogger : LoggerFactory.getLogger(OUTPUT_TRANSFORMATION_JSON_LOGGER);
    }

    public TransformerToIJsonTransformerAdapter(IJsonTransformer transformer) {
        this(transformer, LoggerFactory.getLogger(OUTPUT_TRANSFORMATION_JSON_LOGGER));
    }

    private void logTransformation(Map<String, Object> before, Object after) {
        if (transformerLogger.isInfoEnabled()) {
            try {
                var transformationTuple = toTransformationMap(before, after);
                var tupleString = MAPPER.writeValueAsString(transformationTuple);
                transformerLogger.atInfo().setMessage("{}").addArgument(tupleString).log();
            } catch (Exception e) {
                log.atError().setCause(e).setMessage("Exception converting tuple to string").log();
                transformerLogger.atInfo().setMessage("{ \"error\":\"{}\" }").addArgument(e::getMessage).log();
                throw Lombok.sneakyThrow(e);
            }
        }
    }

    private Map<String, Object> toTransformationMap(Map<String, Object> before, Object after) {
        var transformationMap = new LinkedHashMap<String, Object>();
        transformationMap.put("before", before);
        transformationMap.put("after", after);
        return transformationMap;
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectNodeToMap(Object node) {
        return MAPPER.convertValue(node, Map.class);
    }

    @SneakyThrows
    private static String printMap(Object map) {
        return MAPPER.writeValueAsString(map);
    }

    @SuppressWarnings("unchecked")
    private List<MigrationItem> transformMigrationItem(MigrationItem migrationItem) {
        // Keep untouched original for logging
        final Map<String, Object> originalMap = MAPPER.convertValue(migrationItem, Map.class);
        Object transformedResult = transformer.transformJson(MAPPER.convertValue(migrationItem, Map.class));
        logTransformation(originalMap, transformedResult);

        List<MigrationItem> transformedItems = new ArrayList<>();
        if (transformedResult instanceof List) {
            for (Object item : (List<Object>) transformedResult) {
                transformedItems.add(MAPPER.convertValue(item, MigrationItem.class));
            }
        } else {
            transformedItems.add(MAPPER.convertValue(transformedResult, MigrationItem.class));
        }
        return transformedItems;
    }

    void updateTemplates(Collection<? extends MigrationItem> transformedItems, ObjectNode itemsRoot) {
        itemsRoot.removeAll();
        transformedItems.forEach(item ->
                {
                    log.atInfo().setMessage("Setting new item of type {}, name {}, body {}")
                            .addArgument(item.type)
                            .addArgument(item.name)
                            .addArgument(item.body)
                            .log();
                    itemsRoot.set(item.name, item.body);
                }
        );
    }

    @Override
    public GlobalMetadata transformGlobalMetadata(GlobalMetadata globalData) {
        log.atInfo().setMessage("BeforeJsonGlobal: {}")
            .addArgument(() -> printMap(objectNodeToMap(globalData.toObjectNode()))).log();

        final List<LegacyTemplate> legacyTemplates = new ArrayList<>();
        globalData.getTemplates().fields().forEachRemaining(
                entry -> legacyTemplates.add(new LegacyTemplate(entry.getKey(), (ObjectNode) entry.getValue()))
        );
        final List<IndexTemplate> indexTemplates = new ArrayList<>();
        globalData.getIndexTemplates().fields().forEachRemaining(
                entry -> indexTemplates.add(new IndexTemplate(entry.getKey(), (ObjectNode) entry.getValue()))
        );
        final List<ComponentTemplate> componentTemplates = new ArrayList<>();
        globalData.getComponentTemplates().fields().forEachRemaining(
                entry -> componentTemplates.add(new ComponentTemplate(entry.getKey(), (ObjectNode) entry.getValue()))
        );

        var transformedTemplates = Stream.concat(Stream.concat(
                legacyTemplates.stream(),
                indexTemplates.stream()),
                componentTemplates.stream()
                )
                .flatMap(item -> transformMigrationItem(item).stream())
                .collect(Collectors.toList());

        var transformedLegacy = transformedTemplates.stream()
                .filter(LegacyTemplate.class::isInstance)
                .map(LegacyTemplate.class::cast)
                .collect(Collectors.toList());

        var transformedIndex = transformedTemplates.stream()
                .filter(IndexTemplate.class::isInstance)
                .map(IndexTemplate.class::cast)
                .collect(Collectors.toList());

        var transformedComponent = transformedTemplates.stream()
                .filter(ComponentTemplate.class::isInstance)
                .map(ComponentTemplate.class::cast)
                .collect(Collectors.toList());

        updateTemplates(transformedLegacy, globalData.getTemplates());
        updateTemplates(transformedIndex, globalData.getIndexTemplates());
        updateTemplates(transformedComponent, globalData.getComponentTemplates());

        log.atInfo().setMessage("AfterJsonGlobal: {}")
            .addArgument(() -> printMap(objectNodeToMap(globalData.toObjectNode()))).log();
        return globalData;
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<IndexMetadata> transformIndexMetadata(IndexMetadata indexData) {
        final Map<String, Object> originalInput = MAPPER.convertValue(indexData, Map.class);
        final Map<String, Object> inputJson = MAPPER.convertValue(indexData, Map.class);
        Object afterJson = transformer.transformJson(inputJson);
        logTransformation(originalInput, afterJson);
        if (afterJson instanceof List) {
            return ((List<Map<String, Object>>) afterJson).stream()
                    .map(json -> MAPPER.convertValue(json, IndexMetadata.class))
                    .collect(Collectors.toList());
        } else if (afterJson instanceof Map) {
            return List.of(MAPPER.convertValue(afterJson, IndexMetadata.class));
        } else {
            throw new IllegalArgumentException("Unexpected transformation result type: " + afterJson.getClass());
        }
    }
}
