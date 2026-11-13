package org.opensearch.migrations.bulkload.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs each large-document heap profile in its own JVM so GC history and retained heap cannot
 * bleed from one matrix cell into another.
 */
@EnabledIfSystemProperty(named = "rfs.largeDocument.heapProfile", matches = "true")
class RfsLargeDocumentHeapProfileTest {
    private static final List<Case> CASES = matrix();

    @Test
    @Timeout(value = 90, unit = TimeUnit.MINUTES)
    void sustainedCompletePathHeapProfile() throws Exception {
        int warmupSeconds = Integer.getInteger("rfs.largeDocument.heapProfile.warmupSeconds", 15);
        int measureSeconds = Integer.getInteger("rfs.largeDocument.heapProfile.measureSeconds", 60);
        boolean resume = Boolean.getBoolean("rfs.largeDocument.heapProfile.resume");
        String filter = System.getProperty("rfs.largeDocument.heapProfile.caseFilter", "");
        Path outputDirectory = Path.of(System.getProperty(
            "rfs.largeDocument.heapProfile.outputDirectory",
            "build/reports/rfs-large-document-heap"
        )).toAbsolutePath();
        Files.createDirectories(outputDirectory);

        var results = new ArrayList<String>();
        var measurements = new ArrayList<Measurement>();
        String header = null;
        int executed = 0;
        for (var profileCase : CASES) {
            if (!filter.isEmpty() && !profileCase.name().contains(filter)) {
                continue;
            }
            Path resultPath = outputDirectory.resolve(profileCase.name() + "-result.csv");
            if (!resume || !isReusableResult(resultPath)) {
                runChild(
                    profileCase,
                    outputDirectory,
                    warmupSeconds,
                    profileCase.measureSeconds(measureSeconds)
                );
            } else {
                System.out.println("RFS_HEAP_PROFILE_RESUME " + profileCase.name());
            }
            var lines = Files.readAllLines(resultPath, StandardCharsets.UTF_8);
            assertEquals(2, lines.size(), "unexpected result file " + resultPath);
            if (header == null) {
                header = lines.get(0);
            } else {
                assertEquals(header, lines.get(0));
            }
            results.add(lines.get(1));
            measurements.add(Measurement.parse(lines.get(1)));
            executed++;
        }

        assertTrue(executed > 0, "case filter selected no heap-profile cases: " + filter);
        Path aggregate = outputDirectory.resolve("large-document-heap-profile.csv");
        var output = new ArrayList<String>();
        output.add(header);
        output.addAll(results);
        Files.write(aggregate, output, StandardCharsets.UTF_8);
        System.out.println("RFS_HEAP_PROFILE_AGGREGATE " + aggregate);

        Path factors = outputDirectory.resolve("large-document-heap-factors.csv");
        writeFactorSummary(factors, measurements);
        System.out.println("RFS_HEAP_PROFILE_FACTORS " + factors);
    }

    private static boolean isReusableResult(Path resultPath) {
        try {
            var lines = Files.readAllLines(resultPath, StandardCharsets.UTF_8);
            if (lines.size() != 2
                || !lines.get(0).contains("live_probe_post_full_gc_heap_bytes")) {
                return false;
            }
            String[] values = lines.get(1).split(",", -1);
            return values.length == 38
                && Boolean.parseBoolean(values[18])
                && Boolean.parseBoolean(values[35]);
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeFactorSummary(Path path, List<Measurement> measurements) throws IOException {
        var lines = new ArrayList<String>();
        lines.add("factor,value,worst_case");
        maximumFactor(measurements, false, true)
            .ifPresent(factor -> lines.add(factor.csv("normalTransientFactor")));
        maximumFactor(measurements, false, false)
            .ifPresent(factor -> lines.add(factor.csv("normalRetainedFactor")));
        maximumFactor(measurements, true, true)
            .ifPresent(factor -> lines.add(factor.csv("splitTransientFactor")));
        maximumFactor(measurements, true, false)
            .ifPresent(factor -> lines.add(factor.csv("splitRetainedFactor")));
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static java.util.Optional<Factor> maximumFactor(
        List<Measurement> measurements,
        boolean splitPath,
        boolean transientFactor
    ) {
        return measurements.stream()
            .filter(measurement -> splitPath
                ? measurement.path().equals("SPLIT")
                : measurement.path().equals("NORMAL") || measurement.path().equals("BELOW_THRESHOLD"))
            .map(measurement -> new Factor(
                transientFactor ? measurement.transientFactor() : measurement.retainedFactor(),
                measurement.caseName()
            ))
            .max(java.util.Comparator.comparingDouble(Factor::value));
    }

    private static void runChild(
        Case profileCase,
        Path outputDirectory,
        int warmupSeconds,
        int measureSeconds
    ) throws Exception {
        String javaExecutable = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String maximumHeap = profileCase.concurrency() == 1 ? "24g" : "48g";
        var command = List.of(
            javaExecutable,
            "-Xms1g",
            "-Xmx" + maximumHeap,
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=200",
            "-XX:ActiveProcessorCount=" + profileCase.concurrency(),
            "-XX:+ExitOnOutOfMemoryError",
            "-DmigrationLogLevel=WARN",
            "-Dlog4j2.debug=false",
            "-cp",
            System.getProperty("java.class.path"),
            RfsLargeDocumentHeapProfileHarness.class.getName(),
            outputDirectory.toString(),
            profileCase.pathKind(),
            profileCase.sourceMode(),
            Integer.toString(profileCase.outputMiB()),
            Boolean.toString(profileCase.splitEnabled()),
            Integer.toString(profileCase.concurrency()),
            Integer.toString(warmupSeconds),
            Integer.toString(measureSeconds)
        );
        var process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();
        var output = new StringBuilder();
        var outputReader = new Thread(() -> readOutput(process, output), "rfs-heap-profile-output");
        outputReader.start();

        long timeoutSeconds = warmupSeconds + Math.max(60, measureSeconds) + Duration.ofMinutes(12).toSeconds();
        assertTrue(
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS),
            () -> "heap-profile process exceeded timeout for " + profileCase.name() + ":\n" + output
        );
        outputReader.join(Duration.ofSeconds(10));
        System.out.print(output);
        assertEquals(0, process.exitValue(), () ->
            "heap-profile process failed for " + profileCase.name() + ":\n" + output);
        assertTrue(output.toString().contains("RFS_HEAP_PROFILE_RESULT"), () ->
            "heap-profile process produced no result for " + profileCase.name() + ":\n" + output);
    }

    private static void readOutput(Process process, StringBuilder output) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (output) {
                    output.append(line).append(System.lineSeparator());
                }
            }
        } catch (IOException e) {
            synchronized (output) {
                output.append("output reader failed: ").append(e).append(System.lineSeparator());
            }
        }
    }

    private static List<Case> matrix() {
        var cases = new ArrayList<Case>();
        for (String sourceMode : List.of("PASSTHROUGH", "TRANSFORM")) {
            for (int concurrency : List.of(1, 4)) {
                for (int sizeMiB : List.of(10, 50, 95)) {
                    cases.add(new Case("NORMAL", sourceMode, sizeMiB, false, concurrency));
                }
                cases.add(new Case("BELOW_THRESHOLD", sourceMode, 95, true, concurrency));
                for (int sizeMiB : List.of(190, 390)) {
                    cases.add(new Case("SPLIT", sourceMode, sizeMiB, true, concurrency));
                }
            }
        }
        return List.copyOf(cases);
    }

    private record Case(
        String pathKind,
        String sourceMode,
        int outputMiB,
        boolean splitEnabled,
        int concurrency
    ) {
        private String name() {
            return pathKind.toLowerCase(Locale.ROOT)
                + "-" + sourceMode.toLowerCase(Locale.ROOT)
                + "-" + outputMiB + "mib-c" + concurrency;
        }

        private int measureSeconds(int configuredSeconds) {
            boolean longestSplitTransform =
                pathKind.equals("SPLIT")
                    && sourceMode.equals("TRANSFORM")
                    && outputMiB == 390
                    && concurrency == 4;
            return longestSplitTransform ? Math.max(120, configuredSeconds) : configuredSeconds;
        }
    }

    private record Measurement(
        String caseName,
        String path,
        double transientFactor,
        double retainedFactor
    ) {
        private static Measurement parse(String csv) {
            String[] values = csv.split(",", -1);
            return new Measurement(
                values[0],
                values[1],
                Double.parseDouble(values[15]),
                Double.parseDouble(values[16])
            );
        }
    }

    private record Factor(double value, String caseName) {
        private String csv(String name) {
            return String.join(",", name, String.format(Locale.ROOT, "%.6f", value), caseName);
        }
    }
}
