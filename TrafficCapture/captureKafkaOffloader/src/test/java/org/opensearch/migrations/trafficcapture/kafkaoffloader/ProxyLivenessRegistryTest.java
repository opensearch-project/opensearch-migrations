package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProxyLivenessRegistryTest {
    @Test
    void snapshotsAreExactSortedCopiesAtOneLinearizationPoint() {
        var registry = new ProxyLivenessRegistry();
        registry.register("connection-b", 1);
        registry.register("connection-a", 1);
        registry.register("connection-c", 2);

        var snapshot = registry.snapshot(1);
        registry.remove("connection-a", 1);

        assertEquals(List.of("connection-a", "connection-b"), snapshot);
        assertEquals(List.of("connection-b"), registry.snapshot(1));
        assertEquals(List.of("connection-c"), registry.snapshot(2));
    }

    @Test
    void duplicateOrMismatchedTransitionsFailLoudly() {
        var registry = new ProxyLivenessRegistry();
        registry.register("connection", 1);

        assertThrows(IllegalStateException.class, () -> registry.register("connection", 1));
        assertThrows(IllegalStateException.class, () -> registry.remove("connection", 2));
        registry.remove("connection", 1);
        assertThrows(IllegalStateException.class, () -> registry.remove("connection", 1));
    }
}
