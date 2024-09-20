package org.opensearch.migrations.data;

import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.experimental.UtilityClass;

import static org.opensearch.migrations.data.GeneratedData.createField;

@UtilityClass
public class NycTaxis {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static ObjectNode generateNycTaxisIndex() {
        var index = mapper.createObjectNode();
        var mappings = mapper.createObjectNode();
        var properties = mapper.createObjectNode();
        
        properties.set("tripId", createField("integer"));
        properties.set("pickup_latitude", createField("float"));
        properties.set("pickup_longitude", createField("float"));
        properties.set("dropoff_latitude", createField("float"));
        properties.set("dropoff_longitude", createField("float"));
        properties.set("fare_amount", createField("float"));
        properties.set("passenger_count", createField("integer"));
        properties.set("trip_distance", createField("float"));
        
        mappings.set("properties", properties);
        index.set("mappings", mappings);
        return index;
    }

    public static Stream<ObjectNode> generateNycTaxiDocs(int numDocs) {
        var random = new Random(1L);
        var mapper = new ObjectMapper();

        return IntStream.range(0, numDocs)
            .mapToObj(i -> {
                var doc = mapper.createObjectNode();
                doc.put("tripId", i + 1000);
                doc.put("pickup_latitude", randomLatitude(random));
                doc.put("pickup_longitude", randomLongitude(random));
                doc.put("dropoff_latitude", randomLatitude(random));
                doc.put("dropoff_longitude", randomLongitude(random));
                doc.put("fare_amount", randomFare(random));
                doc.put("passenger_count", randomPassengerCount(random));
                doc.put("trip_distance", randomTripDistance(random));
                return doc;
            }
        );
    }

    private static double randomLatitude(Random random) {
        return 40.5 + (0.2 * random.nextDouble()); // Latitude range around NYC (40.5 to 40.7)
    }

    private static double randomLongitude(Random random) {
        return -74.0 + (0.3 * random.nextDouble()); // Longitude range around NYC (-74.0 to -73.7)
    }

    private static double randomFare(Random random) {
        return 5 + (95 * random.nextDouble()); // Fare range from $5 to $100
    }

    private static int randomPassengerCount(Random random) {
        return random.nextInt(5) + 1; // Passenger count from 1 to 5
    }

    private static double randomTripDistance(Random random) {
        return 0.5 + (10 * random.nextDouble()); // Trip distance between 0.5 to 10 miles
    }
}
