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

public final class TargetExchangeState {
    private TargetExchangeState() {}

    public enum Phase {
        STARTING_ATTEMPT("starting_attempt"),
        SENDING_REQUEST("sending_request"),
        WAITING_FOR_RESPONSE("waiting_for_response"),
        EVALUATING_RETRY("evaluating_retry"),
        RETRY_DELAY("retry_delay"),
        ABORTING("aborting");

        private final String metricLabel;

        Phase(String metricLabel) {
            this.metricLabel = metricLabel;
        }

        public String metricLabel() {
            return metricLabel;
        }
    }

    public enum ChannelState {
        ABSENT("absent"),
        CONNECTING("connecting"),
        ACTIVE("active"),
        INACTIVE("inactive"),
        CLOSING("closing"),
        CLOSED("closed");

        private final String metricLabel;

        ChannelState(String metricLabel) {
            this.metricLabel = metricLabel;
        }

        public String metricLabel() {
            return metricLabel;
        }
    }

    public interface Metrics {
        Metrics NOOP = new Metrics() {
            @Override
            public void phaseChanged(Phase phase, int delta) {
                // Metrics are optional for non-production exchange instances.
            }

            @Override
            public void channelStateChanged(ChannelState state, int delta) {
                // Metrics are optional for non-production connection sessions.
            }
        };

        void phaseChanged(Phase phase, int delta);

        void channelStateChanged(ChannelState state, int delta);
    }
}

*/
// REBUILD-LIMBO-END(G11)