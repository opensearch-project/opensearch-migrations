package org.opensearch.migrations;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads inference endpoints from a source ES cluster and registers corresponding
 * pre-trained models in the target OS cluster via ML Commons API.
 * Returns a mapping of ES inference_id → OS model_id.
 */
@Slf4j
public class InferenceEndpointMigrator {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int TASK_POLL_MAX_ATTEMPTS = 60;
    private static final long TASK_POLL_INTERVAL_MS = 2000;

    static final String DEFAULT_PRETRAINED_MODEL = "huggingface/sentence-transformers/all-MiniLM-L6-v2";
    static final String DEFAULT_PRETRAINED_VERSION = "1.0.2";
    static final String DEFAULT_MODEL_FORMAT = "TORCH_SCRIPT";
    static final int DEFAULT_EMBEDDING_DIMENSION = 384;

    private static final Map<String, String> SIMILARITY_TO_SPACE_TYPE = Map.of(
        "cosine", "cosinesimil",
        "dot_product", "innerproduct",
        "l2_norm", "l2"
    );

    private final String sourceUrl;
    private final String targetUrl;
    private final HttpClient httpClient;

    public InferenceEndpointMigrator(String sourceUrl, String targetUrl) {
        this.sourceUrl = sourceUrl.replaceAll("/+$", "");
        this.targetUrl = targetUrl.replaceAll("/+$", "");
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    public Map<String, String> migrateInferenceEndpoints() {
        var endpoints = readInferenceEndpoints();
        if (endpoints.isEmpty()) {
            return Collections.emptyMap();
        }

        enableMlCommons();

        Map<String, String> modelMappings = new LinkedHashMap<>();
        for (var entry : endpoints.entrySet()) {
            var inferenceId = entry.getKey();
            try {
                var spaceType = resolveSpaceType(entry.getValue());
                var modelId = registerPretrainedModelForEndpoint(inferenceId, spaceType);
                modelMappings.put(inferenceId, modelId);
                log.info("Mapped inference endpoint '{}' → OS model_id '{}'", inferenceId, modelId);
            } catch (Exception e) {
                log.warn("Failed to migrate inference endpoint '{}': {}", inferenceId, e.getMessage());
            }
        }
        return modelMappings;
    }

    private Map<String, JsonNode> readInferenceEndpoints() {
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(sourceUrl + "/_inference")).timeout(TIMEOUT).GET().build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Collections.emptyMap();
            }
            var root = MAPPER.readTree(response.body());
            Map<String, JsonNode> endpoints = new LinkedHashMap<>();
            var arr = root.path("endpoints");
            if (arr.isArray()) {
                for (var ep : arr) {
                    var id = ep.path("inference_id").asText(null);
                    // Skip ES built-in inference endpoints (e.g. .elser-2-elasticsearch)
                    if (id != null && !id.startsWith(".")) endpoints.put(id, ep);
                }
            }
            log.info("Found {} inference endpoints on source cluster", endpoints.size());
            return endpoints;
        } catch (Exception e) {
            log.warn("Failed to read inference endpoints: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private void enableMlCommons() {
        try {
            sendPut("/_cluster/settings", "{\"persistent\":{" +
                "\"plugins.ml_commons.only_run_on_ml_node\":false," +
                "\"plugins.ml_commons.native_memory_threshold\":100}}");
        } catch (Exception e) {
            log.warn("Failed to enable ML Commons: {}", e.getMessage());
        }
    }

    private String registerPretrainedModelForEndpoint(String inferenceId, String spaceType) throws Exception {
        var taskId = registerPretrainedModel();
        var modelId = waitForTask(taskId);
        log.info("Pre-trained model registered for '{}': model_id={}", inferenceId, modelId);

        updateModelConfig(modelId, spaceType);

        try {
            deployModel(modelId);
        } catch (Exception e) {
            log.warn("Model deployment failed for {} (can be deployed later): {}", modelId, e.getMessage());
        }
        return modelId;
    }

    private String registerPretrainedModel() throws Exception {
        var body = "{\"name\":\"" + DEFAULT_PRETRAINED_MODEL + "\"," +
            "\"version\":\"" + DEFAULT_PRETRAINED_VERSION + "\"," +
            "\"model_format\":\"" + DEFAULT_MODEL_FORMAT + "\"}";
        var json = MAPPER.readTree(sendPost("/_plugins/_ml/models/_register", body));
        var taskId = json.path("task_id").asText(null);
        if (taskId == null || taskId.isEmpty()) {
            throw new RuntimeException("No task_id in register response: " + json);
        }
        return taskId;
    }

    private void updateModelConfig(String modelId, String spaceType) throws Exception {
        sendPut("/_plugins/_ml/models/" + modelId,
            "{\"model_config\":{\"model_type\":\"bert\",\"embedding_dimension\":" + DEFAULT_EMBEDDING_DIMENSION +
            ",\"framework_type\":\"SENTENCE_TRANSFORMERS\",\"additional_config\":{\"space_type\":\"" +
            escapeJson(spaceType) + "\"}}}");
    }

    private void deployModel(String modelId) throws Exception {
        var json = MAPPER.readTree(sendPost("/_plugins/_ml/models/" + modelId + "/_deploy", "{}"));
        var taskId = json.path("task_id").asText(null);
        if (taskId != null && !taskId.isEmpty()) {
            waitForTask(taskId);
        }
    }

    private String waitForTask(String taskId) throws Exception {
        for (int i = 0; i < TASK_POLL_MAX_ATTEMPTS; i++) {
            Thread.sleep(TASK_POLL_INTERVAL_MS);
            var request = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl + "/_plugins/_ml/tasks/" + taskId))
                .timeout(TIMEOUT).GET().build();
            var json = MAPPER.readTree(httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body());
            var state = json.path("state").asText();
            if ("COMPLETED".equals(state)) return json.path("model_id").asText();
            if ("FAILED".equals(state)) throw new RuntimeException("Task failed: " + json);
            log.debug("Task {} state: {}", taskId, state);
        }
        throw new RuntimeException("Timed out waiting for task: " + taskId);
    }

    static String resolveSpaceType(JsonNode endpoint) {
        var similarity = endpoint.at("/task_settings/similarity").asText(
            endpoint.at("/service_settings/similarity").asText("cosine"));
        return SIMILARITY_TO_SPACE_TYPE.getOrDefault(similarity, "cosinesimil");
    }

    private String sendPost(String path, String body) throws Exception {
        var request = HttpRequest.newBuilder().uri(URI.create(targetUrl + path)).timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("POST {} → {}: {}", path, response.statusCode(), response.body());
        return response.body();
    }

    private String sendPut(String path, String body) throws Exception {
        var request = HttpRequest.newBuilder().uri(URI.create(targetUrl + path)).timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("PUT {} → {}: {}", path, response.statusCode(), response.body());
        return response.body();
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
