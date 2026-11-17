/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.datahandlers;

import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TargetPacketConsumerOutcomeValueTest {
    @Test
    void noResponseAtPacketBoundaryCarriesTypedDiagnosticRatherThanThrowable() {
        var diagnostic = new TargetAttemptOutcome.NoTargetResponseDiagnostic(
            TargetAttemptOutcome.NoTargetResponseKind.TRANSPORT_FAILURE,
            "connection reset before a response"
        );

        var outcome = new TargetPacketConsumer.PacketSendOutcome.NoTargetResponseObtained(
            diagnostic
        );

        Assertions.assertSame(diagnostic, outcome.diagnostic());
        Assertions.assertEquals("connection reset before a response", outcome.reason());
        var components =
            TargetPacketConsumer.PacketSendOutcome.NoTargetResponseObtained.class
                .getRecordComponents();
        Assertions.assertEquals(1, components.length);
        Assertions.assertEquals(
            TargetAttemptOutcome.NoTargetResponseDiagnostic.class,
            components[0].getType()
        );
    }
}
