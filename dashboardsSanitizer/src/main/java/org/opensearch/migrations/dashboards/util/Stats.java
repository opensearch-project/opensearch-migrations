package org.opensearch.migrations.dashboards.util;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.opensearch.migrations.dashboards.model.Dashboard;

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
     * @param dashboardObject
     */
    public void registerSkipped(Dashboard dashboardObject) {
        if (dashboardObject.getType()== null || dashboardObject.getType().isEmpty()) {
//         it could be a summary line
            return;
        }
        skipped.count++;
        total++;
        int objectTypeCount = skipped.details.getOrDefault(dashboardObject.getType(), 0);
        if (objectTypeCount == 0) {
            skipped.details.put(dashboardObject.getType(), 1);
        } else {
            skipped.details.put(dashboardObject.getType(), objectTypeCount + 1);
        }
    }
    public void registerProcessed(Dashboard dashboardObject) {
        processed++;
        total++;
    }

    /**
     * This method prints the statistics in a JSON format using the Gson library.
     */
    public void printStats() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(this);
        System.out.println(json);
    }

}
