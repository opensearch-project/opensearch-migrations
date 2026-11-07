package org.opensearch.migrations.bulkload.pipeline.provider;

import java.util.List;

import org.opensearch.migrations.bulkload.pipeline.spi.DocumentSourceSpec;

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

    public FailedDocumentStreamSourceSpec {
        SpecJson.requireNonBlank(streamUri, "streamUri");
        SpecJson.requireNonBlank(sessionId, "sessionId");
        indexAllowlist = indexAllowlist == null ? List.of() : List.copyOf(indexAllowlist);
        failureClasses = failureClasses == null ? List.of() : List.copyOf(failureClasses);
    }

    @Override
    public String kind() {
        return FailedDocumentStreamSourceProvider.KIND;
    }

    /** The config form of this spec, which {@code parseSpec} binds back. */
    public ObjectNode toJson() {
        return SpecJson.toJson(this);
    }
}
