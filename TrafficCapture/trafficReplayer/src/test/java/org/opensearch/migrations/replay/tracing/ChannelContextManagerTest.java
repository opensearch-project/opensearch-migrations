package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.tracing.InstrumentationTest;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies that ChannelContextManager uses nodeId-aware keys so that connections
 * from different source nodes with the same connectionId get separate contexts.
 */
public class ChannelContextManagerTest extends InstrumentationTest {

    private IReplayContexts.IChannelKeyContext retainContext(ChannelContextManager mgr, String nodeId, String connectionId) {
        var tsk = PojoTrafficStreamKeyAndContext.build(
            nodeId, connectionId, 0,
            k -> rootContext.createTrafficStreamContextForStreamSource(
                mgr.retainOrCreateContext(k), k)
        );
        // retainOrCreateContext was already called inside the build lambda above,
        // but we need the IChannelKeyContext it returned. Re-call to get it directly.
        return mgr.retainOrCreateContext(tsk);
    }

    @Test
    @SneakyThrows
    void differentNodesSameConnectionId_getSeparateContexts() {
        var mgr = new ChannelContextManager(rootContext);

        var ctxA = retainContext(mgr, "node-A", "conn-1");
        var ctxB = retainContext(mgr, "node-B", "conn-1");

        Assertions.assertNotSame(ctxA, ctxB,
            "Different source nodes with the same connectionId must get separate channel contexts");
        Assertions.assertEquals("node-A", ctxA.getNodeId());
        Assertions.assertEquals("node-B", ctxB.getNodeId());
    }

    @Test
    @SneakyThrows
    void releaseContextFor_doesNotAffectOtherNode() {
        var mgr = new ChannelContextManager(rootContext);

        var ctxA = retainContext(mgr, "node-A", "conn-1");
        var ctxB = retainContext(mgr, "node-B", "conn-1");

        // Release node-A's context
        mgr.releaseContextFor(ctxA);

        // node-B's context must still be retrievable
        var ctxB2 = retainContext(mgr, "node-B", "conn-1");
        Assertions.assertSame(ctxB, ctxB2,
            "node-B's context must survive node-A's release");
    }
}
