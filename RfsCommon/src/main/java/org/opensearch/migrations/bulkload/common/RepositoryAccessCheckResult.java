package org.opensearch.migrations.bulkload.common;

import java.util.List;

/**
 * Provider-neutral result for repository connectivity checks.
 *
 * <p>A repository is partially verified when its prefix can be listed but there is no object
 * available to prove read access. This is intentionally not a failure: a user may be configuring
 * an empty repository before creating the first snapshot.
 */
public record RepositoryAccessCheckResult(
    Status status,
    String provider,
    String location,
    String summary,
    List<Stage> stages
) {
    public enum Status {
        VALID,
        PARTIALLY_VERIFIED,
        FAILED
    }

    public enum StageStatus {
        PASSED,
        PARTIALLY_VERIFIED,
        FAILED,
        SKIPPED
    }

    public record Stage(
        String id,
        String label,
        StageStatus status,
        String message
    ) {
    }

    public boolean isSuccessful() {
        return status != Status.FAILED;
    }

    public static Stage passed(String id, String label, String message) {
        return new Stage(id, label, StageStatus.PASSED, message);
    }

    public static Stage partial(String id, String label, String message) {
        return new Stage(id, label, StageStatus.PARTIALLY_VERIFIED, message);
    }

    public static Stage failed(String id, String label, String message) {
        return new Stage(id, label, StageStatus.FAILED, message);
    }

    public static Stage skipped(String id, String label, String message) {
        return new Stage(id, label, StageStatus.SKIPPED, message);
    }

    public static String failureMessage(Throwable failure) {
        Throwable current = failure;
        String message = null;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message == null ? failure.getClass().getSimpleName() : message;
    }
}
