package org.opensearch.migrations.transform.shim.reporting;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.common.bulk.operations.IndexOperationMeta;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.parsing.BulkResponseParser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenSearch Metrics Sink that uses the RFS bulk indexing infrastructure.
 *
 * Features:
 * - Creates index template with optimized field types on initialization
 * - Bulk-indexes ValidationDocuments with buffering
 * - Retry with bisect-on-failure for partial bulk errors
 * - SigV4 and Basic Auth via ConnectionContext
 * - Time-series daily rolling indices
 */
public class OpenSearchMetricsSink implements MetricsSink {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchMetricsSink.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final String TEMPLATE_NAME_SUFFIX = "-template";
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long MAX_BULK_BYTES = 10L * 1024 * 1024;

    private final RestClient restClient;
    private final String indexPrefix;
    private final int bulkSize;
    private final List<BulkOperationSpec> buffer = new ArrayList<>();
    private long bufferBytes;
    private final ScheduledExecutorService scheduler;

    public OpenSearchMetricsSink(ConnectionContext connectionContext, String indexPrefix,
                                  int bulkSize, long flushIntervalMs) {
        this.restClient = new RestClient(connectionContext);
        this.indexPrefix = indexPrefix;
        this.bulkSize = bulkSize;

        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "metrics-sink-flush");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::flush, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates an index template with explicit field mappings.
     * Called as a preflight step before the shim starts accepting traffic.
     */
    public void createIndexTemplate() {
        try {
            String templateName = indexPrefix + TEMPLATE_NAME_SUFFIX;
            String templateJson = buildIndexTemplateJson();

            HttpResponse response = restClient.putAsync(
                "_index_template/" + templateName, templateJson, null
            ).block();

            if (response != null && response.statusCode >= 200 && response.statusCode < 300) {
                log.info("Successfully created index template: {} for pattern: {}-*",
                    templateName, indexPrefix);
            } else {
                int status = response != null ? response.statusCode : -1;
                String body = response != null ? truncate(response.body, 200) : "null";
                log.warn("Failed to create index template '{}', status {}: {}. Will use dynamic mapping.",
                    templateName, status, body);
            }
        } catch (Exception e) {
            log.error("Error creating index template (non-fatal, will use dynamic mapping): {}",
                e.getMessage(), e);
        }
    }

    String buildIndexTemplateJson() {
        return String.format("""
            {
              "index_patterns": ["%s-*"],
              "priority": 100,
              "template": {
                "mappings": {
                  "properties": {
                    "timestamp": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
                    "request_id": { "type": "keyword" },
                    "collection_name": { "type": "keyword" },
                    "normalized_endpoint": { "type": "keyword" },
                    "solr_hit_count": { "type": "long" },
                    "opensearch_hit_count": { "type": "long" },
                    "hit_count_drift_percentage": { "type": "double" },
                    "solr_qtime_ms": { "type": "long" },
                    "opensearch_took_ms": { "type": "long" },
                    "query_time_delta_ms": { "type": "long" },
                    "original_request": {
                      "properties": {
                        "method": { "type": "keyword" },
                        "uri": { "type": "text", "fields": { "keyword": { "type": "keyword", "ignore_above": 256 } } },
                        "body": { "type": "text" }
                      }
                    },
                    "transformed_request": {
                      "properties": {
                        "method": { "type": "keyword" },
                        "uri": { "type": "text", "fields": { "keyword": { "type": "keyword", "ignore_above": 256 } } },
                        "body": { "type": "text" }
                      }
                    },
                    "comparisons": {
                      "type": "nested",
                      "properties": {
                        "type": { "type": "keyword" },
                        "name": { "type": "keyword" },
                        "keys_match": { "type": "boolean" },
                        "missing_keys": { "type": "keyword" },
                        "extra_keys": { "type": "keyword" },
                        "value_drifts": {
                          "type": "nested",
                          "properties": {
                            "key": { "type": "keyword" },
                            "solr_value": { "type": "double" },
                            "opensearch_value": { "type": "double" },
                            "drift_percentage": { "type": "double" }
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
            var op = toBulkOp(document);
            long opSize = BulkNdjson.getSerializedLength(op);
            synchronized (buffer) {
                buffer.add(op);
                bufferBytes += opSize;
                if (buffer.size() >= bulkSize || bufferBytes >= MAX_BULK_BYTES) {
                    List<BulkOperationSpec> batch = new ArrayList<>(buffer);
                    buffer.clear();
                    bufferBytes = 0;
                    scheduler.execute(() -> sendBulkWithRetry(batch));
                }
            }
        } catch (Exception e) {
            log.error("Error in MetricsSink.submit()", e);
        }
    }

    @Override
    public void flush() {
        try {
            List<BulkOperationSpec> batch;
            synchronized (buffer) {
                if (buffer.isEmpty()) return;
                batch = new ArrayList<>(buffer);
                buffer.clear();
                bufferBytes = 0;
            }
            sendBulkWithRetry(batch);
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
     * Sends a batch with retry and bisect-on-failure, following the RFS pattern.
     * On partial failure, only the failed documents are retried.
     */
    private void sendBulkWithRetry(List<BulkOperationSpec> batch) {
        var pending = new ArrayList<>(batch);
        var allowlist = DocumentExceptionAllowlist.empty();

        for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS && !pending.isEmpty(); attempt++) {
            try {
                byte[] body = BulkNdjson.toBulkNdjsonBytes(pending, MAPPER);
                HttpResponse response = restClient.postAsyncBytes(
                    "_bulk", body, bulkHeaders(), null
                ).block();

                if (response == null) {
                    log.warn("Null response from bulk request, attempt {}", attempt + 1);
                    continue;
                }

                if (response.statusCode >= 200 && response.statusCode < 300) {
                    // Check for partial failures and compact
                    BitSet failedPositions = BulkResponseParser.getFailedPositions(response.body, allowlist);
                    if (failedPositions == null || failedPositions.isEmpty()) {
                        log.debug("Successfully indexed {} documents to {}", batch.size(), generateIndexName());
                        return;
                    }
                    int successCount = compactPending(pending, failedPositions);
                    log.warn("Bulk attempt {}: {} succeeded, {} failed — retrying failures",
                        attempt + 1, successCount, pending.size());
                } else {
                    log.warn("Bulk index returned status {}: {}", response.statusCode,
                        truncate(response.body, 500));
                }
            } catch (Exception e) {
                log.error("Failed to send bulk request, attempt {}", attempt + 1, e);
            }
        }

        if (!pending.isEmpty()) {
            log.error("Gave up on {} documents after {} attempts", pending.size(), MAX_RETRY_ATTEMPTS);
        }
    }

    /** Compact pending list to keep only failed docs. Returns count of successes removed. */
    private int compactPending(ArrayList<BulkOperationSpec> pending, BitSet failedPositions) {
        int writeIdx = 0;
        for (int i = failedPositions.nextSetBit(0); i >= 0; i = failedPositions.nextSetBit(i + 1)) {
            pending.set(writeIdx++, pending.get(i));
        }
        int successCount = pending.size() - writeIdx;
        pending.subList(writeIdx, pending.size()).clear();
        return successCount;
    }

    /** Convert a ValidationDocument to a BulkOperationSpec for the daily index. */
    @SuppressWarnings("unchecked")
    private BulkOperationSpec toBulkOp(ValidationDocument document) {
        Map<String, Object> docMap = MAPPER.convertValue(document, Map.class);
        return IndexOp.builder()
            .operation(IndexOperationMeta.builder().index(generateIndexName()).build())
            .document(docMap)
            .includeDocument(true)
            .build();
    }

    private Map<String, List<String>> bulkHeaders() {
        var headers = new HashMap<String, List<String>>();
        headers.put("Content-Type", List.of("application/x-ndjson"));
        return headers;
    }

    String generateIndexName() {
        return indexPrefix + "-" + LocalDate.now().format(DATE_FORMAT);
    }

    private static String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
