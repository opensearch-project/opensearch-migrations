package org.opensearch.migrations.bulkload.lucene.sidecar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.bulkload.lucene.SourcelessSpillConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link SidecarBuilder} fails fast with
 * {@link SpillBudgetExceededException} when the JVM-wide spill cap configured via
 * {@code rfs.reconstruction.maxSpillBytes} would be exceeded — exercising the
 * fail-fast path that replaces ENOSPC at scale (issue #2961).
 *
 * <p>The volume-headroom probe ({@code rfs.reconstruction.spillFreeSpaceMinBytes})
 * is intentionally not asserted here: producing a real ENOSPC inside a unit test
 * would mean filling the actual test volume, which is hostile to CI. The
 * arithmetic cap path covers the same fail-fast contract.
 */
class SidecarBuilderBudgetTest {

    private Path spillDir;
    private String savedCap;
    private String savedFloor;

    @BeforeEach
    void setUp() throws IOException {
        spillDir = Files.createTempDirectory("sidecar-budget-test-");
        savedCap = System.getProperty(SourcelessSpillConfig.MAX_SPILL_BYTES_PROP);
        savedFloor = System.getProperty(SourcelessSpillConfig.SPILL_FREE_SPACE_MIN_BYTES_PROP);
        SpillBudget.resetForTest();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (savedCap == null) System.clearProperty(SourcelessSpillConfig.MAX_SPILL_BYTES_PROP);
        else System.setProperty(SourcelessSpillConfig.MAX_SPILL_BYTES_PROP, savedCap);
        if (savedFloor == null) System.clearProperty(SourcelessSpillConfig.SPILL_FREE_SPACE_MIN_BYTES_PROP);
        else System.setProperty(SourcelessSpillConfig.SPILL_FREE_SPACE_MIN_BYTES_PROP, savedFloor);
        SpillBudget.resetForTest();
        SidecarTestSupport.rm(spillDir);
    }

    @Test
    void unbounded_default_acceptsLargeWrites() throws IOException {
        // Default cap is unbounded; the builder should accept many records without throwing.
        try (SidecarBuilder b = new SidecarBuilder(spillDir, /*sortBufferBytes=*/1024, /*maxDoc=*/16)) {
            int[] positions = new int[]{0, 1, 2, 3, 4, 5, 6, 7};
            b.accept(/*termId=*/0, /*docId=*/0, positions, positions, positions, positions.length);
            // 8 positions × RECORD_BYTES = 160 bytes reserved.
            assertEquals(8L * SidecarBuilder.RECORD_BYTES, SpillBudget.bytesInFlight());
        }
        // close() releases the reservation.
        assertEquals(0L, SpillBudget.bytesInFlight());
    }

    @Test
    void cap_breached_throwsTypedException() throws IOException {
        // Cap of one record. Any non-trivial accept() will exceed it on the first call.
        System.setProperty(SourcelessSpillConfig.MAX_SPILL_BYTES_PROP,
            Long.toString(SidecarBuilder.RECORD_BYTES));
        SpillBudget.resetForTest();

        try (SidecarBuilder b = new SidecarBuilder(spillDir, /*sortBufferBytes=*/1024, /*maxDoc=*/16)) {
            int[] positions = new int[]{0, 1, 2, 3};
            SpillBudgetExceededException e = assertThrows(
                SpillBudgetExceededException.class,
                () -> b.accept(0, 0, positions, positions, positions, positions.length));
            assertTrue(e.getMessage().contains(SourcelessSpillConfig.MAX_SPILL_BYTES_PROP),
                "exception message should name the prop that was breached: " + e.getMessage());
        }
        // The failed reservation rolled back, and close() released any remainder. Net zero.
        assertEquals(0L, SpillBudget.bytesInFlight());
    }

    @Test
    void cap_just_below_two_records_partial_progress_then_throws() throws IOException {
        // Cap that admits one accept() of 1 record but rejects a second of 2 records.
        long capBytes = 2L * SidecarBuilder.RECORD_BYTES;
        System.setProperty(SourcelessSpillConfig.MAX_SPILL_BYTES_PROP, Long.toString(capBytes));
        SpillBudget.resetForTest();

        try (SidecarBuilder b = new SidecarBuilder(spillDir, /*sortBufferBytes=*/1024, /*maxDoc=*/16)) {
            int[] one = new int[]{0};
            int[] three = new int[]{0, 1, 2};
            b.accept(0, 0, one, one, one, 1); // OK: 20 bytes reserved, in-flight=20.
            assertEquals(SidecarBuilder.RECORD_BYTES, SpillBudget.bytesInFlight());
            // Second call needs 60 more — would push to 80, over the 40-byte cap. Throws.
            assertThrows(SpillBudgetExceededException.class,
                () -> b.accept(0, 1, three, three, three, 3));
            // First record's reservation is intact (only the failed reservation rolled back).
            assertEquals(SidecarBuilder.RECORD_BYTES, SpillBudget.bytesInFlight());
        }
        assertEquals(0L, SpillBudget.bytesInFlight());
    }
}
