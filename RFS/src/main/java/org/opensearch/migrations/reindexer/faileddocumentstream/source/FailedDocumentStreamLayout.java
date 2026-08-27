package org.opensearch.migrations.reindexer.faileddocumentstream.source;

import java.util.Optional;

import lombok.experimental.UtilityClass;

/**
 * The key layout {@code S3FailedDocumentStreamSink} writes, read back the other way.
 *
 * <pre>
 *   &lt;prefix&gt;session=&lt;id&gt;/index=&lt;targetIndex&gt;/worker=&lt;workerId&gt;/failed-document-stream-&lt;ts&gt;-&lt;seq&gt;.ndjson.gz
 * </pre>
 */
@UtilityClass
public class FailedDocumentStreamLayout {

    /** Every record object ends in this; the manifest does not. */
    public static final String RECORD_SUFFIX = ".ndjson.gz";

    static final String SESSION_SEGMENT = "session=";
    static final String INDEX_SEGMENT = "index=";
    static final String WORKER_SEGMENT = "worker=";

    public static final String MANIFEST_FILENAME = "manifest.json";

    /** Trailing slash so keys concatenate; empty stays empty. */
    public static String normalizePrefix(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return raw.endsWith("/") ? raw : raw + "/";
    }

    public static String sessionPrefix(String prefix, String sessionId) {
        return normalizePrefix(prefix) + SESSION_SEGMENT + sessionId + "/";
    }

    public static String manifestKey(String prefix, String sessionId) {
        return sessionPrefix(prefix, sessionId) + MANIFEST_FILENAME;
    }

    public static boolean isRecordObject(String key) {
        return key != null && key.endsWith(RECORD_SUFFIX);
    }

    /** The {@code index=} and {@code worker=} segments, or empty when the key lacks either. */
    public static Optional<RecordLocation> locationOf(String key) {
        if (!isRecordObject(key)) {
            return Optional.empty();
        }
        String index = null;
        String worker = null;
        for (var segment : key.split("/")) {
            if (segment.startsWith(INDEX_SEGMENT)) {
                index = segment.substring(INDEX_SEGMENT.length());
            } else if (segment.startsWith(WORKER_SEGMENT)) {
                worker = segment.substring(WORKER_SEGMENT.length());
            }
        }
        if (index == null || index.isEmpty() || worker == null || worker.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RecordLocation(index, worker));
    }

    public record RecordLocation(String collectionName, String partitionName) {}
}
