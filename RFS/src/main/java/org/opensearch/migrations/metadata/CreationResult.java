package org.opensearch.migrations.metadata;

import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.ToString;

@SuppressWarnings("java:S1210")
@Builder
@Data
@ToString
public class CreationResult implements Comparable<CreationResult>  {
    private final String name;
    private final Exception exception;
    private final CreationFailureType failureType;
    public boolean wasSuccessful() {
        return getFailureType() == null; 
    }

    public boolean wasFatal() {
        return Optional.ofNullable(getFailureType()).map(CreationFailureType::isFatal)
            .orElse(false);
    }

    @AllArgsConstructor
    @Getter
    public enum CreationFailureType {
        ALREADY_EXISTS(false, "already exists"),
        UNABLE_TO_TRANSFORM_FAILURE(true, "failed to transform to the target version"),
        TARGET_CLUSTER_FAILURE(true, "failed on target cluster"),
        INCOMPATIBLE_REPLICA_COUNT_FAILURE(true, "failed due to incompatible replica count for awareness attribute count"),
        SKIPPED_DUE_TO_FILTER(false, "skipped due to filter");

        private final boolean fatal;
        private final String message;
    }

    @Override
    public int compareTo(CreationResult that) {
        if (this.wasSuccessful() != that.wasSuccessful()) {
            return -1;
        }
        if (this.getFailureType() != that.getFailureType()) {
            this.getFailureType().compareTo(that.getFailureType());
        }
        return this.getName().compareTo(that.getName());
    } 
}
