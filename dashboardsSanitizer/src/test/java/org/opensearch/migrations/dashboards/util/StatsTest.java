package org.opensearch.migrations.dashboards.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatsTest {
    private Stats stats;

    @BeforeEach
    public void setup() {
        stats = new Stats();
    }

    @Test
    public void testRegisterSkipped() {
        stats.registerSkipped("dashboard");
        assertEquals(1, stats.getSkipped().getCount());
        assertEquals(1, stats.getTotal());
        assertEquals(1, stats.getSkipped().getDetails().get("dashboard"));

        stats.registerSkipped("visualization");
        stats.registerSkipped("visualization");
        assertEquals(3, stats.getSkipped().getCount());
        assertEquals(3, stats.getTotal());
        assertEquals(2, stats.getSkipped().getDetails().get("visualization"));
    }

    @Test
    public void testRegisterProcessed() {
        stats.registerProcessed();
        assertEquals(1, stats.getProcessed());
        assertEquals(1, stats.getTotal());

        stats.registerProcessed();
        stats.registerProcessed();
        assertEquals(3, stats.getProcessed());
        assertEquals(3, stats.getTotal());
    }

    @Test
    public void testPrintStats() throws JsonProcessingException {
        stats.registerSkipped("dashboard");
        stats.registerSkipped("visualization");
        stats.registerProcessed();

        String expectedJson = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(
            new ObjectMapper().createObjectNode()
                .put("total", 3)
                .put("processed", 1)
                .set("skipped", new ObjectMapper().valueToTree(stats.getSkipped()))
        );

        assertEquals(expectedJson, stats.printStats());
    }
}
