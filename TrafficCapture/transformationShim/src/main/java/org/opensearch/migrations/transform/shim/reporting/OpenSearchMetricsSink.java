package org.opensearch.migrations.transform.shim.reporting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enhanced OpenSearch Metrics Sink that creates explicit index mappings.
 * 
 * Features:
 * - Creates index template with optimized field types on initialization
 * - Bulk-indexes ValidationDocuments with buffering
 * - Non-blocking submission with background flush
 * - Proper keyword types for aggregation fields
 * - Time-series daily rolling indices
 */
public class OpenSearchMetricsSink implements MetricsSink {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchMetricsSink.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final String TEMPLATE_NAME_SUFFIX = "-template";

    private final String reportingClusterUri;
    private final String indexPrefix;
    private final int bulkSize;
    private final HttpClient httpClient;
    private final List<ValidationDocument> buffer = new ArrayList<>();
    private final ScheduledExecutorService scheduler;
    private final String authHeader;

    public OpenSearchMetricsSink(String reportingClusterUri, String indexPrefix,
                                  int bulkSize, long flushIntervalMs,
                                  String username, String password, boolean insecureTls) {
        this.reportingClusterUri = reportingClusterUri.endsWith("/")
                ? reportingClusterUri.substring(0, reportingClusterUri.length() - 1)
                : reportingClusterUri;
        this.indexPrefix = indexPrefix;
        this.bulkSize = bulkSize;

        if (username != null && password != null) {
            this.authHeader = "Basic " + Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes());
        } else {
            this.authHeader = null;
        }

        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        this.httpClient = builder.build();

        // Create index template with explicit mappings on initialization
        createIndexTemplate();

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-sink-flush");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::flush, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates an index template with explicit field mappings.
     * This ensures all shim-metrics-* indices have consistent, optimized mappings.
     */
    private void createIndexTemplate() {
        try {
            String templateName = indexPrefix + TEMPLATE_NAME_SUFFIX;
            String templateJson = buildIndexTemplateJson();

            var requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(reportingClusterUri + "/_index_template/" + templateName))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(templateJson))
                    .timeout(Duration.ofSeconds(10));

            if (authHeader != null) {
                requestBuilder.header("Authorization", authHeader);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Successfully created index template: {} for pattern: {}-*", 
                    templateName, indexPrefix);
            } else {
                log.warn("Failed to create index template '{}', status {}: {}. Will use dynamic mapping.", 
                    templateName, response.statusCode(), truncate(response.body(), 200));
            }
        } catch (Exception e) {
            log.error("Error creating index template (non-fatal, will use dynamic mapping): {}", 
                e.getMessage(), e);
        }
    }

    /**
     * Builds the index template JSON with explicit field type mappings.
     * 
     * Key design decisions:
     * - keyword types for fields needing aggregation (collection_name, endpoint, request_id)
     * - date type for timestamp (enables time-based queries)
     * - long/double types for numeric metrics
     * - nested type for comparisons array (enables independent element queries)
     * - text+keyword dual indexing for URIs (searchable + aggregatable)
     * - headers use dynamic mapping since HTTP header values are always strings
     *   and individual headers vary per request
     */
    private String buildIndexTemplateJson() {
        return String.format("""
            {
              "index_patterns": ["%s-*"],
              "priority": 100,
              "template": {
                "mappings": {
                  "properties": {
                    "timestamp": {
                      "type": "date",
                      "format": "strict_date_optional_time||epoch_millis"
                    },
                    "request_id": {
                      "type": "keyword"
                    },
                    "collection_name": {
                      "type": "keyword"
                    },
                    "normalized_endpoint": {
                      "type": "keyword"
                    },
                    "solr_hit_count": {
                      "type": "long"
                    },
                    "opensearch_hit_count": {
                      "type": "long"
                    },
                    "hit_count_drift_percentage": {
                      "type": "double"
                    },
                    "solr_qtime_ms": {
                      "type": "long"
                    },
                    "opensearch_took_ms": {
                      "type": "long"
                    },
                    "query_time_delta_ms": {
                      "type": "long"
                    },
                    "original_request": {
                      "properties": {
                        "method": {
                          "type": "keyword"
                        },
                        "uri": {
                          "type": "text",
                          "fields": {
                            "keyword": {
                              "type": "keyword",
                              "ignore_above": 256
                            }
                          }
                        },
                        "body": {
                          "type": "text"
                        }
                      }
                    },
                    "transformed_request": {
                      "properties": {
                        "method": {
                          "type": "keyword"
                        },
                        "uri": {
                          "type": "text",
                          "fields": {
                            "keyword": {
                              "type": "keyword",
                              "ignore_above": 256
                            }
                          }
                        },
                        "body": {
                          "type": "text"
                        }
                      }
                    },
                    "comparisons": {
                      "type": "nested",
                      "properties": {
                        "type": {
                          "type": "keyword"
                        },
                        "name": {
                          "type": "keyword"
                        },
                        "keys_match": {
                          "type": "boolean"
                        },
                        "missing_keys": {
                          "type": "keyword"
                        },
                        "extra_keys": {
                          "type": "keyword"
                        },
                        "value_drifts": {
                          "type": "nested",
                          "properties": {
                            "key": {
                              "type": "keyword"
                            },
                            "solr_value": {
                              "type": "double"
                            },
                            "opensearch_value": {
                              "type": "double"
                            },
                            "drift_percentage": {
                              "type": "double"
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            """, indexPrefix);
    }

    @Override
    public void submit(ValidationDocument document) {
        try {
            synchronized (buffer) {
                buffer.add(document);
                if (buffer.size() >= bulkSize) {
                    List<ValidationDocument> batch = new ArrayList<>(buffer);
                    buffer.clear();
                    scheduler.execute(() -> sendBulk(batch));
                }
            }
        } catch (Exception e) {
            log.error("Error in MetricsSink.submit()", e);
        }
    }

    @Override
    public void flush() {
        try {
            List<ValidationDocument> batch;
            synchronized (buffer) {
                if (buffer.isEmpty()) return;
                batch = new ArrayList<>(buffer);
                buffer.clear();
            }
            sendBulk(batch);
        } catch (Exception e) {
            log.error("Error in MetricsSink.flush()", e);
        }
    }

    @Override
    public void close() {
        flush();
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sends a batch of ValidationDocuments to OpenSearch via bulk API.
     * Index is created automatically using the template if it doesn't exist.
     */
    private void sendBulk(List<ValidationDocument> batch) {
        try {
            String indexName = generateIndexName();
            StringBuilder ndjson = new StringBuilder();
            
            for (ValidationDocument doc : batch) {
                // Action line: specifies index operation
                ndjson.append("{\"index\":{\"_index\":\"").append(indexName).append("\"}}\n");
                // Document line: the actual ValidationDocument as JSON
                ndjson.append(MAPPER.writeValueAsString(doc)).append("\n");
            }

            var requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(reportingClusterUri + "/_bulk"))
                    .header("Content-Type", "application/x-ndjson")
                    .POST(HttpRequest.BodyPublishers.ofString(ndjson.toString()))
                    .timeout(Duration.ofSeconds(30));

            if (authHeader != null) {
                requestBuilder.header("Authorization", authHeader);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 300) {
                log.warn("Bulk index to {} returned status {}: {}", 
                    indexName, response.statusCode(), truncate(response.body(), 500));
            } else {
                log.debug("Successfully indexed {} documents to {}", batch.size(), indexName);
                checkPartialFailures(response.body(), batch.size());
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize ValidationDocument batch", e);
        } catch (Exception e) {
            log.error("Failed to send bulk index request to reporting cluster", e);
        }
    }

    /**
     * Checks for partial failures in bulk response.
     * Even if HTTP status is 200, individual documents may have failed.
     */
    private void checkPartialFailures(String responseBody, int totalDocs) {
        try {
            var tree = MAPPER.readTree(responseBody);
            if (tree.has("errors") && tree.get("errors").asBoolean()) {
                int failedCount = 0;
                var items = tree.get("items");
                if (items != null && items.isArray()) {
                    for (var item : items) {
                        var index = item.get("index");
                        if (index != null && index.has("error")) {
                            failedCount++;
                            if (failedCount <= 3) {  // Log first 3 errors
                                log.warn("Document indexing error: {}", 
                                    index.get("error").toPrettyString());
                            }
                        }
                    }
                }
                log.warn("Bulk index had {} failures out of {} documents", failedCount, totalDocs);
            }
        } catch (Exception e) {
            log.debug("Could not parse bulk response for failure check", e);
        }
    }

    /**
     * Generates time-based index name: {prefix}-{yyyy.MM.dd}
     * Example: shim-metrics-2025.03.17
     */
    String generateIndexName() {
        return indexPrefix + "-" + LocalDate.now().format(DATE_FORMAT);
    }

    private static String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
