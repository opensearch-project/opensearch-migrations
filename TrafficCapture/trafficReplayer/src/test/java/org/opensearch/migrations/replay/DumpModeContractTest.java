/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay;

import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The CLI surface Plan A §2.3 treats as a contract: every dump mode name stays accepted, and the two that need
 * machinery a later milestone restores fail with a message naming that milestone.
 *
 * <p>Failing with the milestone named is the whole point. A deferred mode that were simply removed would be
 * rejected as an unrecognised option, which reads to an operator as "this tool never had that feature" rather
 * than "this build does not have it yet" — and a deferred mode that silently produced raw-shaped output for a
 * request that asked for parsed output would be worse still.
 *
 * <p>Asserted against the validator rather than through {@code main}, which catches {@code ParameterException}
 * and calls {@code System.exit(2)} — that would take the test JVM down with it. The CLI dispatch that reaches
 * this validator is covered end-to-end by the {@code dump-raw} evidence in {@code KafkaTopicDumperEvidenceTest}.
 */
class DumpModeContractTest {

    private static TrafficReplayer.Parameters dumpModeParams(String mode) {
        var params = new TrafficReplayer.Parameters();
        params.mode = mode;
        return params;
    }

    /**
     * All three names route to the dump path, which is the half {@code validateDumpModeParams} cannot prove.
     *
     * <p>Without this, dropping {@code dump-http} from {@code isDumpMode} would leave every other test here
     * green: the validator would simply never be reached, and the mode would fall through to the replay path
     * instead of failing with its message. The dispatch and the rejection are two separate claims.
     */
    @Test
    void everyDumpModeNameRoutesToTheDumpPath() {
        for (var mode : new String[] { "dump-raw", "dump-http", "dump-both" }) {
            Assertions.assertTrue(
                TrafficReplayer.isDumpMode(dumpModeParams(mode)),
                () -> mode + " must route to the dump path, or its rejection message is never reached"
            );
        }
    }

    @Test
    void replayModeDoesNotRouteToTheDumpPath() {
        Assertions.assertFalse(TrafficReplayer.isDumpMode(dumpModeParams("replay")));
        Assertions.assertFalse(TrafficReplayer.isDumpMode(dumpModeParams(null)));
    }

    @Test
    void dumpRawIsAvailable() {
        Assertions.assertDoesNotThrow(() -> TrafficReplayer.validateDumpModeParams(dumpModeParams("dump-raw")));
    }

    /** All three published mode names are accepted again, now that source assembly can serve the other two. */
    @Test
    void everyDumpModeIsAvailable() {
        for (var mode : new String[] { "dump-raw", "dump-http", "dump-both" }) {
            Assertions.assertDoesNotThrow(
                () -> TrafficReplayer.validateDumpModeParams(dumpModeParams(mode)),
                () -> mode + " is a published CLI mode and must be accepted"
            );
        }
    }

    @Test
    void fileInputInADumpModeFailsNamingTheMilestoneThatRestoresIt() {
        var params = dumpModeParams("dump-raw");
        params.inputFilename = "/some/captured/file";

        var thrown = Assertions.assertThrows(
            ParameterException.class,
            () -> TrafficReplayer.validateDumpModeParams(params)
        );

        Assertions.assertTrue(
            thrown.getMessage().contains("G5"),
            () -> "file input is deferred, so its rejection must name the milestone: " + thrown.getMessage()
        );
    }

    /** A consumer group is meaningless for a read-only inspection, and accepting one would imply otherwise. */
    @Test
    void aConsumerGroupIsRejectedInDumpModes() {
        var params = dumpModeParams("dump-raw");
        params.kafkaTrafficGroupId = "some-group";

        Assertions.assertThrows(
            ParameterException.class,
            () -> TrafficReplayer.validateDumpModeParams(params)
        );
    }
}
