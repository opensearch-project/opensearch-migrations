package org.opensearch.migrations.replay.lifecycle;

import lombok.NonNull;

public sealed interface RecordDisposition permits RecordDisposition.Commit, RecordDisposition.Retain {
    enum Action {
        COMMIT("commit"),
        RETAIN("retain");

        private final String metricLabel;

        Action(String metricLabel) {
            this.metricLabel = metricLabel;
        }

        public String metricLabel() {
            return metricLabel;
        }
    }

    Action action();

    String reasonCode();

    record Commit(@NonNull String reasonCode) implements RecordDisposition {
        @Override
        public Action action() {
            return Action.COMMIT;
        }
    }

    record Retain(@NonNull String reasonCode) implements RecordDisposition {
        @Override
        public Action action() {
            return Action.RETAIN;
        }
    }
}
