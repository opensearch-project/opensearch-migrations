package org.opensearch.migrations.dashboards;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import org.opensearch.migrations.dashboards.converter.DashboardConverter;
import org.opensearch.migrations.dashboards.converter.IndexPatternConverter;
import org.opensearch.migrations.dashboards.converter.QueryConverter;
import org.opensearch.migrations.dashboards.converter.SavedObjectConverter;
import org.opensearch.migrations.dashboards.converter.SearchConverter;
import org.opensearch.migrations.dashboards.converter.UrlConverter;
import org.opensearch.migrations.dashboards.converter.VisualizationConverter;
import org.opensearch.migrations.dashboards.savedobjects.SavedObject;
import org.opensearch.migrations.dashboards.savedobjects.SavedObjectParser;
import org.opensearch.migrations.dashboards.util.Stats;

import com.fasterxml.jackson.core.JsonProcessingException;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Sanitizer {

    private static final Sanitizer INSTANCE = new Sanitizer();

    private final SavedObjectParser savedObjectParser = new SavedObjectParser();

    private Queue<SavedObject> processingQueue = new java.util.LinkedList<>();

    @Getter
    private Stats stats = new Stats();

    @SuppressWarnings("rawtypes")
    private Map<String, SavedObjectConverter> typeConverters = new HashMap<>() {{
        put("index-pattern", new IndexPatternConverter());
        put("search", new SearchConverter());
        put("dashboard", new DashboardConverter());
        put("visualization", new VisualizationConverter());
        put("url", new UrlConverter());
        put("query", new QueryConverter());
    }};

    private Set<String> notSupportedTypes = new HashSet<>() {{
        add("map");
        add("canvas-workpad");
        add("canvas-element");
        add("graph-workspace");
        add("connector");
        add("rule");
        add("action");
        add("config");
        add("lens");
    }};

    public static Sanitizer getInstance() {
        return INSTANCE;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public String sanitize(String jsonString) {
        String result = null;
        try {
            final SavedObject savedObject = savedObjectParser.load(jsonString);

            if (savedObject == null) {
                return null;
            }

            if (typeConverters.containsKey(savedObject.getObjectType())) {
                this.addNewObjectToQueue(savedObject);
                result = this.processQueue();
                
                stats.registerProcessed();
                
            } else if (notSupportedTypes.contains(savedObject.getObjectType())) {
                log.warn("The object type {} is not supported.", savedObject.getObjectType());
                stats.registerSkipped(savedObject.getObjectType());
            } else {
                log.warn("No converter found for the object type {}.", savedObject.getObjectType());
                stats.registerSkipped(savedObject.getObjectType());
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse the provided string as a json.", e);
        } catch (IllegalArgumentException e) {
            log.error("Failed to load the saved object.", e);
        }
        return result;
    }


    @SuppressWarnings("unchecked")
    public String processQueue() {
        final StringBuffer buffer = new StringBuffer();
        while (!processingQueue.isEmpty()) {
            final SavedObject savedObject = processingQueue.poll();

            buffer.append(typeConverters.get(savedObject.getObjectType()).convert(savedObject).jsonAsString());
            buffer.append(System.lineSeparator());      
        }

        buffer.deleteCharAt(buffer.length() - System.lineSeparator().length());
        return buffer.toString();
    }

    public void addNewObjectToQueue(SavedObject savedObject) {
        processingQueue.add(savedObject);
    }

    // supporting Unit testing
    public void clearQueue() {
        processingQueue.clear();
    }
    // supporting Unit testing
    public int getQueueSize() {
        return processingQueue.size();
    }
}
