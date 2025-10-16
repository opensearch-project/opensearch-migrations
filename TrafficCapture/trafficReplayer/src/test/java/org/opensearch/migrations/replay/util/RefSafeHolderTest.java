package org.opensearch.migrations.replay.util;

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
