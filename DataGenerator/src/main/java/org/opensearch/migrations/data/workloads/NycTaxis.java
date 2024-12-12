package org.opensearch.migrations.data.workloads;

import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.opensearch.migrations.data.IFieldCreator;
import org.opensearch.migrations.data.IRandomDataBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Workload based off of nyc_taxis
 * https://github.com/opensearch-project/opensearch-benchmark-workloads/tree/main/nyc_taxis
 */
public class NycTaxis implements Workload, IFieldCreator, IRandomDataBuilders {

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
        return mapper.createObjectNode()
            .<ObjectNode>set("mappings", mapper.createObjectNode()
                .<ObjectNode>set("properties", mapper.createObjectNode()
                    .<ObjectNode>set("cab_color", fieldKeyword())
                    .<ObjectNode>set("dropoff_datetime", fieldDateISO())
                    .<ObjectNode>set("dropoff_location", fieldGeoPoint())
                    .<ObjectNode>set("ehail_fee", fieldScaledFloat())
                    .<ObjectNode>set("extra", fieldScaledFloat())
                    .<ObjectNode>set("fare_amount", fieldScaledFloat())
                    .<ObjectNode>set("improvement_surcharge", fieldScaledFloat())
                    .<ObjectNode>set("mta_tax", fieldScaledFloat())
                    .<ObjectNode>set("passenger_count", fieldInt())
                    .<ObjectNode>set("payment_type", fieldKeyword())
                    .<ObjectNode>set("pickup_datetime", fieldDateISO())
                    .<ObjectNode>set("pickup_location", fieldGeoPoint())
                    .<ObjectNode>set("rate_code_id", fieldKeyword())
                    .<ObjectNode>set("store_and_fwd_flag", fieldKeyword())
                    .<ObjectNode>set("surcharge", fieldScaledFloat())
                    .<ObjectNode>set("tip_amount", fieldScaledFloat())
                    .<ObjectNode>set("tolls_amount", fieldScaledFloat())
                    .<ObjectNode>set("total_amount", fieldScaledFloat())
                    .<ObjectNode>set("trip_distance", fieldScaledFloat())
                    .<ObjectNode>set("trip_type", fieldKeyword())
                    .<ObjectNode>set("vendor_id", fieldKeyword())
                    .<ObjectNode>set("vendor_name", fieldText()))
                .put("dynamic", "strict"))
            .set("settings", defaultSettings);
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
                double totalAmount = randomDouble(random, 5.0, 50.0);
                String pickupTime = randomTimeISOString(currentTime, random);
                String dropOffTime = randomTimeISOString(currentTime, random);
                double tolls = randomDouble(random, 0.0, 5.0);
                double fare = randomDouble(random, 5.0, 50.0);
                double extra = randomDouble(random, 0.0, 1.0);
                double tripDistance = randomDouble(random, 0.5, 20.0);
                double tip = randomDouble(random, 0.0, 15.0);
                return mapper.createObjectNode()
                .<ObjectNode>put("total_amount", totalAmount)
                .<ObjectNode>put("improvement_surcharge", 0.3)
                .<ObjectNode>set("pickup_location", randomLocationInNyc(random))
                .<ObjectNode>put("pickup_datetime", pickupTime)
                .<ObjectNode>put("trip_type", randomTripType(random))
                .<ObjectNode>put("dropoff_datetime", dropOffTime)
                .<ObjectNode>put("rate_code_id", "1")
                .<ObjectNode>put("tolls_amount", tolls)
                .<ObjectNode>set("dropoff_location", randomLocationInNyc(random))
                .<ObjectNode>put("passenger_count", random.nextInt(4) + 1)
                .<ObjectNode>put("fare_amount", fare)
                .<ObjectNode>put("extra", extra)
                .<ObjectNode>put("trip_distance", tripDistance)
                .<ObjectNode>put("tip_amount", tip)
                .<ObjectNode>put("store_and_fwd_flag", randomStoreAndFwdFlag(random))
                .<ObjectNode>put("payment_type", randomPaymentType(random))
                .<ObjectNode>put("mta_tax", 0.5)
                .<ObjectNode>put("vendor_id", randomVendorId(random));
            }
        );
    }

    private ArrayNode randomLocationInNyc(Random random) {
        var location = mapper.createArrayNode();
        location.add(randomDouble(random, -74.05, -73.75)); // Longitude
        location.add(randomDouble(random, 40.63, 40.85));   // Latitude
        return location;
    }

    private String randomTripType(Random random) {
        return randomElement(TRIP_TYPES, random);
    }

    private String randomPaymentType(Random random) {
        return randomElement(PAYMENT_TYPES, random);
    }

    private String randomStoreAndFwdFlag(Random random) {
        return randomElement(STORE_AND_FWD_FLAGS, random);
    }

    private String randomVendorId(Random random) {
        return randomElement(VENDOR_IDS, random);
    }
}
