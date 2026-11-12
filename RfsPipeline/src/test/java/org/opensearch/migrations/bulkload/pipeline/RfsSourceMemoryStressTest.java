package org.opensearch.migrations.bulkload.pipeline;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("rfsMemoryStressTest")
class RfsSourceMemoryStressTest {

    @Test
    void feederRemainsBoundedInAnIsolatedSmallHeapProcess() throws Exception {
        int durationSeconds = Integer.getInteger("rfs.sourceMemoryStress.seconds", 60);
        String javaExecutable = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        var command = new ArrayList<>(List.of(
            javaExecutable,
            "-Xms128m",
            "-Xmx128m",
            "-XX:ActiveProcessorCount=2",
            "-XX:+ExitOnOutOfMemoryError",
            "-cp",
            System.getProperty("java.class.path"),
            RfsSourceMemoryStressHarness.class.getName(),
            Integer.toString(durationSeconds)
        ));
        var process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();
        var output = new StringBuilder();
        var outputReader = new Thread(() -> readOutput(process, output), "rfs-memory-stress-output");
        outputReader.start();

        assertTrue(
            process.waitFor(durationSeconds + 45L, TimeUnit.SECONDS),
            () -> "stress process exceeded timeout; output:\n" + output
        );
        outputReader.join(Duration.ofSeconds(10));
        System.out.print(output);

        assertEquals(0, process.exitValue(), () -> "stress process failed:\n" + output);
        assertTrue(
            output.toString().contains("RFS_SOURCE_MEMORY_STRESS_PASS"),
            () -> "stress process did not report success:\n" + output
        );
    }

    private static void readOutput(Process process, StringBuilder output) {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
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
}
