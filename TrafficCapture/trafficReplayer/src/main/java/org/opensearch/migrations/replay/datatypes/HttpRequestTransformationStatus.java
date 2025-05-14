package org.opensearch.migrations.replay.datatypes;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;

public interface HttpRequestTransformationStatus {
    final class Completed implements HttpRequestTransformationStatus {
        public static final Completed INSTANCE = new Completed();

        private Completed() {
        }

        @Override
        public boolean isCompleted() {
            return true;
        }
    }

    final class Skipped implements HttpRequestTransformationStatus {
        public static final Skipped INSTANCE = new Skipped();

        private Skipped() {
        }

        @Override
        public boolean isSkipped() {
            return true;
        }
    }

    @Getter
    @AllArgsConstructor
    final class Error implements HttpRequestTransformationStatus {
        @NonNull
        private final Throwable exception;

        @Override
        public boolean isError() {
            return true;
        }
    }

    static Completed completed() {
        return Completed.INSTANCE;
    }

    static Skipped skipped() {
        return Skipped.INSTANCE;
    }

    static Error makeError(Throwable e) {
        return new Error(e);
    }

    default Throwable getException() {
        return null;
    }

    default boolean isCompleted() {
        return false;
    }

    default boolean isSkipped() {
        return false;
    }

    default boolean isError() {
        return false;
    }
}
