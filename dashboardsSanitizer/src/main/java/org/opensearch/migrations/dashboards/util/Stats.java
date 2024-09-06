package org.opensearch.migrations.dashboards.util;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Data;
import lombok.Getter;

/**
 * This class is used to keep track of the statistics of the processing.
 * It provides methods to register skipped and processed dashboard objects, and print the statistics in a JSON format.
 */
@Data
public class Stats {
    private int total;
    private int processed;
    private StatsDetails skipped;

    @Getter
    public static class StatsDetails {
        private int count;
        private Map<String, Integer> details = new HashMap<>();

    }

    public Stats() {
        skipped = new StatsDetails();
    }

    /**
     * This method registers a skipped dashboard object by incrementing the count and updating the details map.
     * It skips the registration if the dashboard object type is null or empty.
     * @param type The type of the exported object.
     */
    public void registerSkipped(String type) {
        skipped.count++;
        total++;
        int objectTypeCount = skipped.details.getOrDefault(type, 0);
        if (objectTypeCount == 0) {
            skipped.details.put(type, 1);
        } else {
            skipped.details.put(type, objectTypeCount + 1);
        }
    }
    public void registerProcessed() {
        processed++;
        total++;
    }

    /**
     * This method prints the statistics in a JSON format using the Gson library.
     * @throws IllegalArgumentException 
     * @throws JsonProcessingException 
     */
    public String printStats() throws JsonProcessingException, IllegalArgumentException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
            mapper.createObjectNode()
                .put("total", total)
                .put("processed", processed)
                .set("skipped", mapper.valueToTree(skipped)));
    }

}
