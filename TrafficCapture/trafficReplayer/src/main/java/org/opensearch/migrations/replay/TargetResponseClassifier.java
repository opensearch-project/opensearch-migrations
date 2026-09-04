package org.opensearch.migrations.replay;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.opensearch.migrations.ExceptionTypeAllowlist;
import org.opensearch.migrations.replay.http.retries.BulkItemErrorClassifier;
import org.opensearch.migrations.replay.http.retries.OpenSearchDefaultRetry;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetOutcome;
import org.opensearch.migrations.replay.util.RefSafeHolder;

import lombok.NonNull;

/**
 * Separates target retry exhaustion from the operator policy that may authorize a durable skip.
 */
public final class TargetResponseClassifier {
    private static final Pattern BULK_PATH = Pattern.compile("^(/[^/]*)?/_bulk(/.*)?$");

    private final BulkItemErrorClassifier retryClassifier;
    private final ExceptionTypeAllowlist poisonAllowlist;

    public TargetResponseClassifier(
        @NonNull BulkItemErrorClassifier retryClassifier,
        @NonNull ExceptionTypeAllowlist poisonAllowlist
    ) {
        this.retryClassifier = retryClassifier;
        this.poisonAllowlist = poisonAllowlist;
    }

    public TargetOutcome<TransformedTargetRequestAndResponseList> classify(
        @NonNull TransformedTargetRequestAndResponseList summary
    ) {
        if (summary.getResponseList().isEmpty()) {
            return failed("Target exchange completed without a response");
        }
        var response = summary.getResponseList().get(summary.getResponseList().size() - 1);
        if (response.getError() != null) {
            return new TargetOutcome.Failed<>(response.getError());
        }
        if (response.getRawResponse() == null) {
            return failed("Target exchange completed without an HTTP response");
        }

        int status = response.getRawResponse().status().code();
        if (!isBulkRequest(summary)) {
            return status >= 200 && status < 300
                ? new TargetOutcome.Succeeded<>(summary)
                : failed("Target returned HTTP " + status);
        }
        if (status != 200) {
            return failed("Bulk target returned HTTP " + status);
        }

        var inspection = OpenSearchDefaultRetry.inspectBulkResponse(
            response.getResponseAsByteBuf(),
            retryClassifier
        );
        if (inspection.analysis() == null) {
            return failed("Bulk target response could not be classified");
        }
        return switch (inspection.analysis()) {
            case NO_ERRORS -> new TargetOutcome.Succeeded<>(summary);
            case HAS_RETRYABLE_ERRORS ->
                failed("Bulk target response still contains retryable or unclassified failures");
            case ONLY_NON_RETRYABLE_ERRORS -> {
                var errorTypes = inspection.errorTypes();
                if (!errorTypes.isEmpty() && errorTypes.stream().allMatch(poisonAllowlist::isAllowed)) {
                    yield new TargetOutcome.ClassifiedSkip<>(
                        summary,
                        "operator allowlisted bulk failures " + errorTypes
                    );
                }
                yield failed("Bulk target response contains non-allowlisted failures " + errorTypes);
            }
        };
    }

    private boolean isBulkRequest(TransformedTargetRequestAndResponseList summary) {
        if (summary.requestPackets == null || summary.requestPackets.isEmpty()) {
            return false;
        }
        try (var request = RefSafeHolder.create(summary.requestPackets.asCompositeByteBufRetained())) {
            var parsed = HttpByteBufFormatter.parseHttpRequestFromBufs(Stream.of(request.get()), 0);
            return parsed != null && BULK_PATH.matcher(parsed.uri()).matches();
        }
    }

    private static TargetOutcome.Failed<TransformedTargetRequestAndResponseList> failed(String message) {
        return new TargetOutcome.Failed<>(new IllegalStateException(message));
    }
}
