package org.opensearch.migrations.replay.util;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: (stale API or unresolved reference; see build log). Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class RefSafeHolderTest {
    @Test
    void testCloseWithNonNullResource() {
        try (MockedStatic<ReferenceCountUtil> mockedStatic = mockStatic(ReferenceCountUtil.class)) {
            Object resource = new Object();
            try (var ignored = RefSafeHolder.create(resource)) {
                mockedStatic.verify(() -> ReferenceCountUtil.release(resource), times(0));
            }
            mockedStatic.verify(() -> ReferenceCountUtil.release(resource), times(1));
        }
    }

    @Test
    void testCloseWithNullResource() {
        try (var holder = RefSafeHolder.create(null)) {
            assertNull(holder.get());
            assertEquals("RefSafeHolder{null}", holder.toString());
        }
    }
}

*/
// REBUILD-LIMBO-END(G10)