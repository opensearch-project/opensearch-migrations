package org.opensearch.migrations.replay.datatypes;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;

public abstract class HttpRequestTransformationStatus {
    private HttpRequestTransformationStatus() {}

    public static final class Completed extends HttpRequestTransformationStatus {
        public static final Completed INSTANCE = new Completed();

        private Completed() {}

        @Override
        public boolean isCompleted() {
            return true;
        }
    }

    public static final class Skipped extends HttpRequestTransformationStatus {
        public static final Skipped INSTANCE = new Skipped();

        private Skipped() {}

        @Override
        public boolean isSkipped() {
            return true;
        }
    }

    @Getter
    @AllArgsConstructor
    public static final class Error extends HttpRequestTransformationStatus {
        @NonNull
        private final Throwable exception;

        @Override
        public boolean isError() {
            return true;
        }
    }


    public static Completed completed() { return Completed.INSTANCE; }
    public static Skipped skipped() { return Skipped.INSTANCE; }
    public static Error makeError(Throwable e) { return new Error(e); }

    public Throwable getException() {
        return null;
    }

    public boolean isCompleted() {
        return false;
    }
    public boolean isSkipped() {
        return false;
    }
    public boolean isError() {
        return false;
    }
}
