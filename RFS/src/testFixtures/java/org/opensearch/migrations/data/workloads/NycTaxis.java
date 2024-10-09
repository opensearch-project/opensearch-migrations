package org.opensearch.migrations.data.workloads;

import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.opensearch.migrations.data.FieldBuilders.createField;
import static org.opensearch.migrations.data.RandomDataBuilders.randomDouble;
import static org.opensearch.migrations.data.RandomDataBuilders.randomElement;
import static org.opensearch.migrations.data.RandomDataBuilders.randomTimeISOString;

public class NycTaxis implements Workload {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String[] TRIP_TYPES = {"1", "2"};
    private static final String[] PAYMENT_TYPES = {"1", "2", "3", "4"};
    private static final String[] STORE_AND_FWD_FLAGS = {"Y", "N"};
    private static final String[] VENDOR_IDS = {"1", "2"};

    @Override
    public List<String> indexNames() {
        return List.of("nyc_taxis");
    }

    @Override
    public ObjectNode createIndex(ObjectNode defaultSettings) {
        var properties = mapper.createObjectNode();
        properties.set("surcharge", createScaledFloatField());
        properties.set("dropoff_datetime", createDateField());
        properties.set("trip_type", createField("keyword"));
        properties.set("mta_tax", createScaledFloatField());
        properties.set("rate_code_id", createField("keyword"));
        properties.set("passenger_count", createField("integer"));
        properties.set("pickup_datetime", createDateField());
        properties.set("tolls_amount", createScaledFloatField());
        properties.set("tip_amount", createField("half_float"));
        properties.set("payment_type", createField("keyword"));
        properties.set("extra", createScaledFloatField());
        properties.set("vendor_id", createField("keyword"));
        properties.set("store_and_fwd_flag", createField("keyword"));
        properties.set("improvement_surcharge", createScaledFloatField());
        properties.set("fare_amount", createScaledFloatField());
        properties.set("ehail_fee", createScaledFloatField());
        properties.set("cab_color", createField("keyword"));
        properties.set("dropoff_location", createField("geo_point"));
        properties.set("vendor_name", createField("text"));
        properties.set("total_amount", createScaledFloatField());
        properties.set("trip_distance", createScaledFloatField());
        properties.set("pickup_location", createField("geo_point"));

        var mappings = mapper.createObjectNode();
        mappings.set("properties", properties);
        mappings.put("dynamic", "strict");

        var index = mapper.createObjectNode();
        index.set("mappings", mappings);
        index.set("settings", defaultSettings);

        return index;
    }

    private static ObjectNode createScaledFloatField() {
        var property = mapper.createObjectNode();
        property.put("type", "scaled_float");
        property.put("scaling_factor", 100);
        return property;
    }

    private static ObjectNode createDateField() {
        var field = mapper.createObjectNode();
        field.put("type", "date");
        field.put("format", "yyyy-MM-dd HH:mm:ss");
        return field;
    }

    @Override
    public Stream<ObjectNode> createDocs(int numDocs) {
        var random = new Random(1L);
        var currentTime = System.currentTimeMillis();

        return IntStream.range(0, numDocs)
                .mapToObj(i -> {
                    var doc = mapper.createObjectNode();
                    doc.put("total_amount", randomDouble(random, 5.0, 50.0));
                    doc.put("improvement_surcharge", 0.3);
                    doc.set("pickup_location", randomLocation(random));
                    doc.put("pickup_datetime", randomTimeISOString(currentTime, random));
                    doc.put("trip_type", randomTripType(random));
                    doc.put("dropout_datetime", randomTimeISOString(currentTime, random));
                    doc.put("rate_code_id", "1");
                    doc.put("tolls_amount", randomDouble(random, 0.0, 5.0));
                    doc.set("dropoff_location", randomLocation(random));
                    doc.put("passenger_count", random.nextInt(4) + 1);
                    doc.put("fare_amount", randomDouble(random, 5.0, 50.0));
                    doc.put("extra", randomDouble(random, 0.0, 1.0));
                    doc.put("trip_distance", randomDouble(random, 0.5, 20.0));
                    doc.put("tip_amount", randomDouble(random, 0.0, 15.0));
                    doc.put("store_and_fwd_flag", randomStoreAndFwdFlag(random));
                    doc.put("payment_type", randomPaymentType(random));
                    doc.put("mta_tax", 0.5);
                    doc.put("vendor_id", randomVendorId(random));
                    return doc;
                });
    }

    private static ArrayNode randomLocation(Random random) {
        var location = mapper.createArrayNode();
        location.add(randomDouble(random, -74.05, -73.75)); // Longitude
        location.add(randomDouble(random, 40.63, 40.85));   // Latitude
        return location;
    }

    private static String randomTripType(Random random) {
        return randomElement(TRIP_TYPES, random);
    }

    private static String randomPaymentType(Random random) {
        return randomElement(PAYMENT_TYPES, random);
    }

    private static String randomStoreAndFwdFlag(Random random) {
        return randomElement(STORE_AND_FWD_FLAGS, random);
    }

    private static String randomVendorId(Random random) {
        return randomElement(VENDOR_IDS, random);
    }
}
