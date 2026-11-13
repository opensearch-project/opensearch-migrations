package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An opt-in JUnit runner for collecting stable, easily archived splitter timings in CI or on a developer host.
 *
 * <p>Run with {@code ./gradlew :RFS:jsonSplitterPerformanceTest}. JMH remains the authoritative microbenchmark;
 * this test provides a convenient end-to-end matrix using the same document shape as the cluster transform.
 */
@EnabledIfSystemProperty(named = "rfs.jsonSplitter.performance", matches = "true")
class JsonObjectSplitterPerformanceTest {
    private static final int MIB = 1024 * 1024;
    private static final int FRAGMENT_MIB = 100;
    private static final List<Integer> DOCUMENT_MIB = List.of(10, 100, 190, 390);

    @Test
    void recordLargeNestedDocumentSplitProfile() throws IOException {
        System.out.println("document_mib,document_bytes,fragments,split_millis");
        for (int documentMiB : DOCUMENT_MIB) {
            byte[] document = JsonObjectSplitterBenchmark.nestedDocument(
                documentMiB * MIB, 4096, 512, 8);

            // Prime parser and class initialization outside the recorded interval.
            JsonObjectSplitter.split(
                JsonObjectSplitterBenchmark.nestedDocument(MIB, 4096, 16, 3), FRAGMENT_MIB * MIB);

            long started = System.nanoTime();
            List<byte[]> fragments = JsonObjectSplitter.split(document, FRAGMENT_MIB * MIB);
            double elapsedMillis = (System.nanoTime() - started) / 1_000_000.0;

            assertTrue(fragments.stream().allMatch(fragment -> fragment.length <= FRAGMENT_MIB * MIB));
            assertTrue(fragments.size() >= Math.ceil(document.length / (double) (FRAGMENT_MIB * MIB)));
            System.out.printf(Locale.ROOT, "%d,%d,%d,%.3f%n",
                documentMiB, document.length, fragments.size(), elapsedMillis);
        }
    }
}
