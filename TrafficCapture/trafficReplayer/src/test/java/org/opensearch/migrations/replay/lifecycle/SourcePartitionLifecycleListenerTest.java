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

import java.lang.reflect.Method;
import java.util.List;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SourcePartitionLifecycleListenerTest {
    private static final List<SourcePartitionKey> PARTITIONS =
        List.of(new SourcePartitionKey("topic", 0, 1));

    @Test
    void requiredLifecycleMethodsCannotBeSilentlyInherited() {
        for (Method method : SourcePartitionLifecycleListener.class.getDeclaredMethods()) {
            if (method.isSynthetic() || java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Assertions.assertFalse(method.isDefault(), method + " must require an explicit implementation");
        }
    }

    @Test
    void unconfiguredListenerFailsEveryLifecycleEvent() {
        var listener = new UnconfiguredSourcePartitionLifecycleListener();

        Assertions.assertThrows(IllegalStateException.class, () -> listener.onAssigned(PARTITIONS));
        Assertions.assertThrows(IllegalStateException.class, () -> listener.onRevoked(PARTITIONS));
        Assertions.assertThrows(IllegalStateException.class, () -> listener.onRetired(PARTITIONS));
    }
}

*/
// REBUILD-LIMBO-END(G11)