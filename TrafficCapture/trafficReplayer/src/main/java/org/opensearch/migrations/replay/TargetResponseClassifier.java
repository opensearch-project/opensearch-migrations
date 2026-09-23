package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G5) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: IRequestResponsePacketPair . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G5)
/*

import java.util.OptionalInt;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.opensearch.migrations.ExceptionTypeAllowlist;
import org.opensearch.migrations.replay.http.retries.BulkItemErrorClassifier;
import org.opensearch.migrations.replay.http.retries.OpenSearchDefaultRetry;
import org.opensearch.migrations.replay.util.RefSafeHolder;

import io.netty.handler.codec.http.HttpResponse;
import lombok.NonNull;

*/
// REBUILD-LIMBO-END(G5)
/**
 * Separates target retry exhaustion from the operator policy that may authorize a durable skip.
 */
// REBUILD-LIMBO-START(G5)
/*
public final class TargetResponseClassifier {
    private static final Pattern BULK_PATH = Pattern.compile("^(/[^/]*)?/_bulk(/.*)?$");

    public sealed interface TargetResponseClassification
        permits TargetResponseClassification.Successful,
            TargetResponseClassification.Unsuccessful,
            TargetResponseClassification.Allowlisted {

        record Successful() implements TargetResponseClassification {}

        record Unsuccessful(@NonNull Throwable cause) implements TargetResponseClassification {}

        record Allowlisted(@NonNull String reason) implements TargetResponseClassification {}
    }

    private final BulkItemErrorClassifier retryClassifier;
    private final ExceptionTypeAllowlist poisonAllowlist;

    public TargetResponseClassifier(
        @NonNull BulkItemErrorClassifier retryClassifier,
        @NonNull ExceptionTypeAllowlist poisonAllowlist
    ) {
        this.retryClassifier = retryClassifier;
        this.poisonAllowlist = poisonAllowlist;
    }

    public TargetResponseClassification classify(
        @NonNull TransformedTargetRequestAndResponseList summary,
        @NonNull IRequestResponsePacketPair source
    ) {
        if (summary.getResponseList().isEmpty()) {
            return failed("Target exchange completed without a response");
        }
        var response = summary.getResponseList().get(summary.getResponseList().size() - 1);
        if (response.getError() != null) {
            return new TargetResponseClassification.Unsuccessful(response.getError());
        }
        if (response.getRawResponse() == null) {
            return failed("Target exchange completed without an HTTP response");
        }

        int status = response.getRawResponse().status().code();
        if (!isBulkRequest(summary)) {
            return classifyHttpStatus(source, status, "Target");
        }
        if (status != 200) {
            return classifyHttpStatus(source, status, "Bulk target");
        }

        var inspection = OpenSearchDefaultRetry.inspectBulkResponse(
            response.getResponseAsByteBuf(),
            retryClassifier
        );
        if (inspection.analysis() == null) {
            return failed("Bulk target response could not be classified");
        }
        return switch (inspection.analysis()) {
            case NO_ERRORS -> new TargetResponseClassification.Successful();
            case HAS_RETRYABLE_ERRORS ->
                failed("Bulk target response still contains retryable or unclassified failures");
            case ONLY_NON_RETRYABLE_ERRORS -> {
                var errorTypes = inspection.errorTypes();
                if (!errorTypes.isEmpty() && errorTypes.stream().allMatch(poisonAllowlist::isAllowed)) {
                    yield new TargetResponseClassification.Allowlisted(
                        "operator allowlisted bulk failures " + errorTypes
                    );
                }
                yield failed("Bulk target response contains non-allowlisted failures " + errorTypes);
            }
        };
    }

    private TargetResponseClassification classifyHttpStatus(
        IRequestResponsePacketPair source,
        int targetStatus,
        String targetDescription
    ) {
        if (targetStatus >= 200 && targetStatus < 300) {
            return new TargetResponseClassification.Successful();
        }
        var sourceStatus = sourceStatus(source);
        if (sourceStatus.isPresent() && !isSuccessful(sourceStatus.getAsInt())) {
            return new TargetResponseClassification.Successful();
        }
        var sourceDescription = sourceStatus.isPresent()
            ? Integer.toString(sourceStatus.getAsInt())
            : "unknown";
        return failed(
            targetDescription + " returned HTTP " + targetStatus + " while the source returned " + sourceDescription
        );
    }

    private static boolean isSuccessful(int status) {
        return status >= 200 && status < 300;
    }

    private OptionalInt sourceStatus(IRequestResponsePacketPair source) {
        var responseData = source.getResponseData();
        if (responseData == null) {
            return OptionalInt.empty();
        }
        try (var responseBytes = RefSafeHolder.create(responseData.asByteBuf())) {
            var response = HttpByteBufFormatter.processHttpMessageFromBufs(
                HttpByteBufFormatter.HttpMessageType.RESPONSE,
                Stream.of(responseBytes.get())
            );
            return response instanceof HttpResponse httpResponse
                ? OptionalInt.of(httpResponse.status().code())
                : OptionalInt.empty();
        }
    }

    private boolean isBulkRequest(TransformedTargetRequestAndResponseList summary) {
        var requestPackets = summary.requestPackets();
        if (requestPackets == null || requestPackets.isEmpty()) {
            return false;
        }
        try (var request = RefSafeHolder.create(requestPackets.asCompositeByteBufRetained())) {
            var parsed = HttpByteBufFormatter.parseHttpRequestFromBufs(Stream.of(request.get()), 0);
            return parsed != null && BULK_PATH.matcher(parsed.uri()).matches();
        }
    }

    private static TargetResponseClassification.Unsuccessful failed(String message) {
        return new TargetResponseClassification.Unsuccessful(new IllegalStateException(message));
    }
}

*/
// REBUILD-LIMBO-END(G5)