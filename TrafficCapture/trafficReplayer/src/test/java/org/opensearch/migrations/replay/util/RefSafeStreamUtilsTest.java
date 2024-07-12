package org.opensearch.migrations.replay.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;

import static org.junit.jupiter.api.Assertions.*;

class RefSafeStreamUtilsTest {

    @Test
    void refSafeMap_shouldMapAndReleaseResources() {
        Stream<String> inputStream = Stream.of("a", "b", "c");

        List<TestReferenceCounted> result;
        try (
            Stream<TestReferenceCounted> mappedStream = RefSafeStreamUtils.refSafeMap(
                inputStream,
                TestReferenceCounted::new
            )
        ) {
            result = mappedStream.collect(Collectors.toList());
        }

        assertEquals(3, result.size());
        assertTrue(result.stream().allMatch(TestReferenceCounted::isReleased));
    }

    @Test
    void refSafeTransform_shouldTransformAndReleaseResources() {
        Stream<String> inputStream = Stream.of("a", "b", "c");

        List<TestReferenceCounted> refCountedObjects = new ArrayList<>();
        List<String> result = RefSafeStreamUtils.refSafeTransform(inputStream, value -> {
            TestReferenceCounted refCounted = new TestReferenceCounted(value);
            refCountedObjects.add(refCounted);
            return refCounted;
        }, stream -> stream.map(TestReferenceCounted::getValue).collect(Collectors.toList()));

        assertEquals(List.of("a", "b", "c"), result);
        assertTrue(refCountedObjects.stream().allMatch(TestReferenceCounted::isReleased));
    }

    @Test
    void refSafeMap_shouldHandleExceptionDuringMapping() {
        List<String> inputStreamConsumedObjects = new ArrayList<>();
        Stream<String> inputStream = Stream.of("a", "b", "c", "d", "e").peek(inputStreamConsumedObjects::add);

        List<TestReferenceCounted> refCountedObjects = new ArrayList<>();
        assertThrows(RuntimeException.class, () -> {
            try (Stream<TestReferenceCounted> mappedStream = RefSafeStreamUtils.refSafeMap(inputStream, value -> {
                if (value.equals("d")) {
                    throw new RuntimeException("Simulated exception");
                }
                TestReferenceCounted refCounted = new TestReferenceCounted(value);
                refCountedObjects.add(refCounted);
                return refCounted;
            })) {
                try {
                    mappedStream.collect(Collectors.toList());
                } finally {
                    // Expect no release until try-with-resources close
                    assertEquals(3, refCountedObjects.size());
                    assertTrue(refCountedObjects.stream().allMatch(Predicate.not(TestReferenceCounted::isReleased)));
                }
            }
        });
        assertEquals(4, inputStreamConsumedObjects.size());
        assertEquals(3, refCountedObjects.size());
        assertTrue(refCountedObjects.stream().allMatch(TestReferenceCounted::isReleased));
    }

    private static class TestReferenceCounted extends AbstractReferenceCounted {
        private final String value;
        private boolean released;

        TestReferenceCounted(String value) {
            this.value = value;
        }

        String getValue() {
            return value;
        }

        boolean isReleased() {
            return released;
        }

        @Override
        public boolean release() {
            if (released) {
                throw new AssertionError("TestReferenceCounted object released twice");
            }
            try {
                return super.release();
            } finally {
                released = true;
            }
        }

        @Override
        protected void deallocate() {
            // No-op
        }

        @Override
        public ReferenceCounted touch(Object hint) {
            return this;
        }
    }
}
