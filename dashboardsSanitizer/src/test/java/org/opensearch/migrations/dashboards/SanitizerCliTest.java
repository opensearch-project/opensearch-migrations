package org.opensearch.migrations.dashboards;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.*;
import java.util.Scanner;

import static org.mockito.Mockito.*;

class SanitizerCliTest {

    @Mock
    private Scanner mockScanner;

    @Mock
    private BufferedWriter mockWriter;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testRun() throws IOException {
        // Set up test data
        String sourceFile = "test-source.ndjson";
        String outputFile = "test-output.ndjson";

        // Create an instance of SanitizerCli
        SanitizerCli cli = new SanitizerCli();
        cli.sourceFile = sourceFile;
        cli.outputFile = outputFile;

        // Mock the behavior of the scanner and writer
        when(mockScanner.hasNextLine()).thenReturn(true, true, false);
        when(mockScanner.nextLine()).thenReturn("line1", "line2");
        when(mockWriter.write(anyString())).thenReturn(null);
        doNothing().when(mockWriter).newLine();
        doNothing().when(mockWriter).flush();

        // Call the run method
        cli.run();

        // Verify that the expected methods were called
        verify(mockScanner, times(2)).hasNextLine();
        verify(mockScanner, times(2)).nextLine();
        verify(mockWriter, times(2)).write(anyString());
        verify(mockWriter, times(2)).newLine();
        verify(mockWriter, times(2)).flush();
    }

    @Test
    void testMain() {
        // Set up test data
        String[] args = {"-s", "test-source.ndjson", "-o", "test-output.ndjson"};

        // Create an instance of SanitizerCli
        SanitizerCli cli = new SanitizerCli();

        // Mock the behavior of JCommander
        JCommanderStub jCommanderStub = new JCommanderStub();
        JCommanderStub.BuilderStub builderStub = new JCommanderStub.BuilderStub();
        jCommanderStub.setBuilder(builderStub);
        when(builderStub.addObject(cli)).thenReturn(builderStub);
        when(builderStub.build()).thenReturn(jCommanderStub);

        // Call the main method
        SanitizerCli.main(args);

        // Verify that the expected methods were called
        verify(builderStub).addObject(cli);
        verify(builderStub).build();
        verify(jCommanderStub).parse(args);
        verify(jCommanderStub).usage();
        verify(cli).run();
    }

    @Test
    void testSanitizeDashboardsFromFile() throws IOException {
        // Set up test data
        String line1 = "line1";
        String line2 = "line2";
        String sanitizedLine1 = "sanitizedLine1";
        String sanitizedLine2 = "sanitizedLine2";

        // Create an instance of SanitizerCli
        SanitizerCli cli = new SanitizerCli();

        // Mock the behavior of the scanner and writer
        when(mockScanner.hasNextLine()).thenReturn(true, true, false);
        when(mockScanner.nextLine()).thenReturn(line1, line2);
        when(mockWriter.write(sanitizedLine1)).thenReturn(null);
        when(mockWriter.write(sanitizedLine2)).thenReturn(null);
        doNothing().when(mockWriter).newLine();
        doNothing().when(mockWriter).flush();

        // Call the sanitizeDashboardsFromFile method
        Stats stats = SanitizerCli.sanitizeDashboardsFromFile(mockScanner, mockWriter);

        // Verify that the expected methods were called
        verify(mockScanner, times(2)).hasNextLine();
        verify(mockScanner, times(2)).nextLine();
        verify(mockWriter).write(sanitizedLine1);
        verify(mockWriter).write(sanitizedLine2);
        verify(mockWriter, times(2)).newLine();
        verify(mockWriter, times(2)).flush();

        // Verify the returned stats
        // (You may need to adjust the assertions based on your actual implementation)
        assertNotNull(stats);
        assertEquals(0, stats.getSanitizedCount());
        assertEquals(2, stats.getProcessedCount());
    }
}