package org.opensearch.migrations.replay.lifecycle;

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
