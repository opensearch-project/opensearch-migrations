package org.opensearch.migrations.bulkload.pipeline.provider;

import java.util.List;
import java.util.Objects;

import org.opensearch.migrations.bulkload.pipeline.spi.DocumentSourceSpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Configuration for redriving a sealed failure-stream session.
 *
 * <p>The root and the session id are kept apart because that is how the writing side is configured,
 * and because composing the layout below the root belongs to this source.
 *
 * @param streamUri      {@code s3://bucket/optional/prefix} root of the failure stream
 * @param sessionId      the session to read; its objects live under {@code <prefix>session=<id>/}
 * @param s3Region       region for the bucket, else null
 * @param endpoint       custom S3 endpoint, else null
 * @param indexAllowlist target indices to redrive; empty means all
 * @param failureClasses failure classes to redrive; empty means all
 */
public record FailedDocumentStreamSourceSpec(
    String streamUri,
    String sessionId,
    String s3Region,
    String endpoint,
    List<String> indexAllowlist,
    List<String> failureClasses
) implements DocumentSourceSpec {

    static final String FIELD_STREAM_URI = "streamUri";
    static final String FIELD_SESSION_ID = "sessionId";
    static final String FIELD_S3_REGION = "s3Region";
    static final String FIELD_ENDPOINT = "endpoint";
    static final String FIELD_INDEX_ALLOWLIST = "indexAllowlist";
    static final String FIELD_FAILURE_CLASSES = "failureClasses";

    public FailedDocumentStreamSourceSpec {
        Objects.requireNonNull(streamUri, "streamUri must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        indexAllowlist = indexAllowlist == null ? List.of() : List.copyOf(indexAllowlist);
        failureClasses = failureClasses == null ? List.of() : List.copyOf(failureClasses);
    }

    @Override
    public String kind() {
        return FailedDocumentStreamSourceProvider.KIND;
    }

    /** Serializes back to the config {@link #fromJson} parses; see {@code EsSnapshotSourceSpec#toJson}. */
    public ObjectNode toJson() {
        var node = JsonNodeFactory.instance.objectNode();
        node.put(FIELD_STREAM_URI, streamUri);
        node.put(FIELD_SESSION_ID, sessionId);
        SpecJson.putIfPresent(node, FIELD_S3_REGION, s3Region);
        SpecJson.putIfPresent(node, FIELD_ENDPOINT, endpoint);
        var allowlist = node.putArray(FIELD_INDEX_ALLOWLIST);
        indexAllowlist.forEach(allowlist::add);
        var classes = node.putArray(FIELD_FAILURE_CLASSES);
        failureClasses.forEach(classes::add);
        return node;
    }

    static FailedDocumentStreamSourceSpec fromJson(JsonNode config) {
        return new FailedDocumentStreamSourceSpec(
            SpecJson.requiredString(config, FIELD_STREAM_URI),
            SpecJson.requiredString(config, FIELD_SESSION_ID),
            SpecJson.optionalString(config, FIELD_S3_REGION),
            SpecJson.optionalString(config, FIELD_ENDPOINT),
            SpecJson.stringList(config, FIELD_INDEX_ALLOWLIST),
            SpecJson.stringList(config, FIELD_FAILURE_CLASSES));
    }
}
