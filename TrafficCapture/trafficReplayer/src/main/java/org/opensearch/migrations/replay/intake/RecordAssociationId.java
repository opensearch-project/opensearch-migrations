/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.intake;

import java.util.Objects;

import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;

/**
 * Which replay-intake operation still needs a Kafka record's observation.
 *
 * <p>{@code kafkaLLD §8.1} defines the operation identity as one of an incomplete source-request assembly,
 * a {@code ReplayRequestId}, terminal source-connection processing, or another explicitly documented
 * replay-intake operation. This is that union, and a fourth variant arrives when a fourth operation is
 * documented — not before.
 *
 * <p>These are <strong>not</strong> among the eight process-local identities in
 * {@code org.opensearch.migrations.replay.identity}. {@code §2} defines those; this is intake-local
 * bookkeeping that keys them, which is why {@link Request} carries a {@code ReplayRequestId} rather than
 * being one. The dependency runs intake → identity and not the other way.
 */
public sealed interface RecordAssociationId {

    /**
     * A source request that has not been reconstituted yet, so no {@link ReplayRequestId} exists for it.
     *
     * <p>Relabeled to {@link Request} when the parser completes the request ({@code §8.2}), and removed
     * outright when the incomplete request expires without reconstitution — that case requires no tuple.
     *
     * @param capturedRequestOrdinal which request within the connection lifetime, so two requests in one
     *                               record do not collide ({@code §8.3})
     */
    record RequestAssembly(ConnectionProcessingId connectionProcessingId, long capturedRequestOrdinal)
        implements RecordAssociationId {
        public RequestAssembly {
            Objects.requireNonNull(connectionProcessingId, "connectionProcessingId");
            if (capturedRequestOrdinal < 0) {
                throw new IllegalArgumentException(
                    "capturedRequestOrdinal must not be negative: " + capturedRequestOrdinal);
            }
        }

        @Override
        public String toString() {
            return connectionProcessingId + ".assembling" + capturedRequestOrdinal;
        }
    }

    /**
     * A reconstituted request. Its associations remain until {@code RequestProcessingFinished} arrives
     * after durable tuple output, which is why a record carrying only source-response bytes stays
     * unfinished that long ({@code §9.2}).
     */
    record Request(ReplayRequestId replayRequestId) implements RecordAssociationId {
        public Request {
            Objects.requireNonNull(replayRequestId, "replayRequestId");
        }

        @Override
        public String toString() {
            return replayRequestId.toString();
        }
    }

    /**
     * Terminal processing for one source-connection lifetime — the captured close in {@code §9.3}.
     *
     * <p>One per lifetime and therefore unindexed: {@code §9} makes a lifetime {@code open},
     * {@code explicitly closed} or {@code expired}, so there is no second terminal operation to
     * distinguish from the first.
     */
    record TerminalConnection(ConnectionProcessingId connectionProcessingId)
        implements RecordAssociationId {
        public TerminalConnection {
            Objects.requireNonNull(connectionProcessingId, "connectionProcessingId");
        }

        @Override
        public String toString() {
            return connectionProcessingId + ".terminal";
        }
    }
}
