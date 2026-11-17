/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.intake;

import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;

import lombok.NonNull;

/**
 * Names a replay-intake operation that still depends on observations held by one or more Kafka records.
 *
 * <p>Records cannot become committable merely because their bytes have been parsed: an incomplete request
 * assembly, a reconstituted request awaiting durable processing, or terminal connection handling may still
 * depend on them. This sealed union gives each such operation a stable bookkeeping key so every contributing
 * record can retain it until that operation finishes.</p>
 *
 * <p>An assembly association is relabelled to a request association when parsing allocates the final
 * {@link ReplayRequestId}. That transition occurs without a gap, preventing the completing record from
 * appearing unreferenced and becoming committable between the two phases.</p>
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
    record RequestAssembly(@NonNull ConnectionProcessingId connectionProcessingId, long capturedRequestOrdinal)
        implements RecordAssociationId {
        public RequestAssembly {
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
    record Request(@NonNull ReplayRequestId replayRequestId) implements RecordAssociationId {
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
    record TerminalConnection(@NonNull ConnectionProcessingId connectionProcessingId)
        implements RecordAssociationId {
        @Override
        public String toString() {
            return connectionProcessingId + ".terminal";
        }
    }
}
