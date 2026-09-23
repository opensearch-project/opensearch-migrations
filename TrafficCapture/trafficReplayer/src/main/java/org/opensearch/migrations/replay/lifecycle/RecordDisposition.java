package org.opensearch.migrations.replay.lifecycle;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Carried verbatim. This was the pre-rebuild implementation of a responsibility the design
// reassigns, so it is the input to that refactor rather than something to re-derive. Resolve it to
// dead, keep, or refactor deliberately -- see AGENTS.md section 8a, and read this before writing

// REBUILD-LIMBO-START(G11)
/*

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

*/
// REBUILD-LIMBO-END(G11)