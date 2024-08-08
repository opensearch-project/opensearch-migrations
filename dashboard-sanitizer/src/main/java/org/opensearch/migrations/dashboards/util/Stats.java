package org.opensearch.migrations.dashboards.util;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.opensearch.migrations.dashboards.model.Dashboard;

import lombok.Data;
import lombok.Getter;

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

    public void registerSkipped(Dashboard dashboardObject) {
        if (dashboardObject.getType().isEmpty()) {
//            try {
//                System.out.println(new ObjectMapper().writeValueAsString(dashboardObject));
//            } catch (JsonProcessingException e) {
//                e.printStackTrace();
//            }
            return;
        }
        skipped.count++;
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
    public void printStats() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(this);
        System.out.println(json);
    }

}
