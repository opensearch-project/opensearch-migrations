package org.opensearch.migrations.replay.lifecycle;

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
