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

/**
 * Workload based off of nyc_taxis
 * https://github.com/opensearch-project/opensearch-benchmark-workloads/tree/main/nyc_taxis
 */
public class NycTaxis implements Workload {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String[] TRIP_TYPES = {"1" , "2"}; 
    private static final String[] PAYMENT_TYPES = {"1", " 2", "3",  "4"};
    private static final String[] STORE_AND_FWD_FLAGS =  {"Y", "N"};
    private static final String[] VENDOR_IDS = {"1", "2"};

    @Override
    public List<String> indexNames() {
        return List.of("nyc_taxis");
    }

    /**
     * Mirroring index configuration from 
     * https://github.com/opensearch-project/opensearch-benchmark-workloads/blob/main/nyc_taxis/index.json
     */
    @Override
    public ObjectNode createIndex(ObjectNode defaultSettings) {
        var properties = mapper.createObjectNode();
        properties.set("cab_color", createField("keyword"));
        properties.set("dropoff_datetime", createDateField());
        properties.set("dropoff_location", createField("geo_point"));
        properties.set("ehail_fee", createScaledFloatField());
        properties.set("extra", createScaledFloatField());
        properties.set("fare_amount", createScaledFloatField());
        properties.set("improvement_surcharge", createScaledFloatField());
        properties.set("mta_tax", createScaledFloatField());
        properties.set("passenger_count", createField("integer"));
        properties.set("payment_type", createField("keyword"));
        properties.set("pickup_datetime", createDateField());
        properties.set("pickup_location", createField("geo_point"));
        properties.set("rate_code_id", createField("keyword"));
        properties.set("store_and_fwd_flag", createField("keyword"));
        properties.set("surcharge", createScaledFloatField());
        properties.set("tip_amount", createScaledFloatField());
        properties.set("tolls_amount", createScaledFloatField());
        properties.set("total_amount", createScaledFloatField());
        properties.set("trip_distance", createScaledFloatField());
        properties.set("trip_type", createField("keyword"));
        properties.set("vendor_id", createField("keyword"));
        properties.set("vendor_name", createField("text"));
       

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

    /**
     * Example generated document:
     {
         "total_amount": 48.96852646813233,
         "improvement_surcharge": 0.3,
         "pickup_location": [
             -73.96071975181356,
             40.761333931139575
         ],
         "pickup_datetime": "2024-10-10 03:39:22",
         "trip_type": "2",
         "dropoff_datetime": "2024-10-09 17:54:43",
         "rate_code_id": "1",
         "tolls_amount": 0.9381693846282485,
         "dropoff_location": [
             -73.9126110288055,
             40.715247495239176
         ],
         "passenger_count": 4,
         "fare_amount": 21.07896409187173,
         "extra": 0.5291259818883527,
         "trip_distance": 1.124182854144491,
         "tip_amount": 0.372383809916233,
         "store_and_fwd_flag": "Y",
         "payment_type": "3",
         "mta_tax": 0.5,
         "vendor_id": "2"
     }
     */
    @Override
    public Stream<ObjectNode> createDocs(int numDocs) {
        var currentTime = System.currentTimeMillis();

        return IntStream.range(0, numDocs)
            .mapToObj(i -> {
                var random = new Random(i);
                var doc = mapper.createObjectNode();
                doc.put("total_amount", randomDouble(random, 5.0, 50.0));
                doc.put("improvement_surcharge", 0.3);
                doc.set("pickup_location", randomLocationInNyc(random));
                doc.put("pickup_datetime", randomTimeISOString(currentTime, random));
                doc.put("trip_type", randomTripType(random));
                doc.put("dropoff_datetime", randomTimeISOString(currentTime, random));
                doc.put("rate_code_id", "1");
                doc.put("tolls_amount", randomDouble(random, 0.0, 5.0));
                doc.set("dropoff_location", randomLocationInNyc(random));
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
            }
        );
    }

    private static ArrayNode randomLocationInNyc(Random random) {
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
