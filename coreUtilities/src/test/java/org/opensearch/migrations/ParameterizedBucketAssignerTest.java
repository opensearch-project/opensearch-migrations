package org.opensearch.migrations;

import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Slf4j
@Tag("isolatedTest")
@ExtendWith(BucketTestExtension.class)
public class ParameterizedBucketAssignerTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19})
    void testBuckets(int number) {
        log.error("Running test case: " + number);
    }

    static class CustomClass {
        int number;
        Instant instant;

        public CustomClass(int number) {
            this.number = number;
            this.instant = Instant.now();
        }
    }

    static Iterable<CustomClass> customClassProvider() {
        return IntStream.rangeClosed(1, 20)
            .mapToObj(CustomClass::new)
            .collect(Collectors.toList());
    }

    @ParameterizedTest
    @MethodSource("customClassProvider")
    void testBucketsCustom(CustomClass customClass) {
        log.error("Running test case: " + customClass.number);
    }

}
