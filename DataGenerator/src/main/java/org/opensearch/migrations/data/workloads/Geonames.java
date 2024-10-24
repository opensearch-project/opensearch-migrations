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
 * Workload based off of Geonames
 * https://github.com/opensearch-project/opensearch-benchmark-workloads/tree/main/geonames
 */
public class Geonames implements Workload, IFieldCreator, IRandomDataBuilders {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String[] COUNTRY_CODES = { "US", "DE", "FR", "GB", "CN", "IN", "BR" };

    @Override
    public List<String> indexNames() {
        return List.of("geonames");
    }

    /**
     * Mirroring index configuration from 
     * https://github.com/opensearch-project/opensearch-benchmark-workloads/blob/main/geonames/index.json
     */
    @Override
    public ObjectNode createIndex(ObjectNode defaultSettings) {
        return mapper.createObjectNode()
            .<ObjectNode>set("mappings", mapper.createObjectNode()
                .<ObjectNode>put("dynamic", "strict")
                .<ObjectNode>set("properties",  mapper.createObjectNode()
                    .<ObjectNode>set("geonameid", fieldLong())
                    .<ObjectNode>set("name", fieldRawTextKeyword())
                    .<ObjectNode>set("asciiname", fieldRawTextKeyword())
                    .<ObjectNode>set("alternatenames", fieldRawTextKeyword())
                    .<ObjectNode>set("feature_class", fieldRawTextKeyword())
                    .<ObjectNode>set("feature_code", fieldRawTextKeyword())
                    .<ObjectNode>set("cc2", fieldRawTextKeyword())
                    .<ObjectNode>set("admin1_code", fieldRawTextKeyword())
                    .<ObjectNode>set("admin2_code", fieldRawTextKeyword())
                    .<ObjectNode>set("admin3_code", fieldRawTextKeyword())
                    .<ObjectNode>set("admin4_code", fieldRawTextKeyword())
                    .<ObjectNode>set("elevation", fieldInt())
                    .<ObjectNode>set("population", fieldLong())
                    .<ObjectNode>set("dem", fieldRawTextKeyword())
                    .<ObjectNode>set("timezone", fieldRawTextKeyword())
                    .<ObjectNode>set("location", fieldGeoPoint())
                    .<ObjectNode>set("country_code", fieldRawTextKeyword()
                        .put("fielddata", true))))
            .<ObjectNode>set("settings", defaultSettings);
    }

    /**
     * Example generated document:
       {
           "geonameid": 1018,
           "name": "City19",
           "asciiname": "City19",
           "alternatenames": "City19",
           "feature_class": "FCl19",
           "feature_code": "FCo19",
           "country_code": "DE",
           "cc2": "cc219",
           "admin1_code": "admin19",
           "population": 621,
           "dem": "699",
           "timezone": "TZ19",
           "location": [
               -104.58261595311684,
               -58.923212235479056
           ]
       }
     */
    @Override
    public Stream<ObjectNode> createDocs(int numDocs) {
        return IntStream.range(0, numDocs)
            .mapToObj(i -> {
                // These documents are have a low degree of uniqueness,
                // there is an opportunity to augment them by using Random more.
                var random = new Random(i);
                return mapper.createObjectNode()
                    .<ObjectNode>put("geonameid", i + 1000)
                    .<ObjectNode>put("name", "City" + (i + 1))
                    .<ObjectNode>put("asciiname", "City" + (i + 1))
                    .<ObjectNode>put("alternatenames", "City" + (i + 1))
                    .<ObjectNode>put("feature_class", "FCl" + (i + 1))
                    .<ObjectNode>put("feature_code", "FCo" + (i + 1))
                    .<ObjectNode>put("country_code", randomCountryCode(random))
                    .<ObjectNode>put("cc2", "cc2" + (i + 1))
                    .<ObjectNode>put("admin1_code", "admin" + (i + 1))
                    .<ObjectNode>put("population", random.nextInt(1000))
                    .<ObjectNode>put("dem", random.nextInt(1000) + "")
                    .<ObjectNode>put("timezone", "TZ" + (i + 1))
                    .<ObjectNode>set("location", randomLocation(random));
            }
        );
    }

    private ArrayNode randomLocation(Random random) {
        var location = mapper.createArrayNode();
        location.add(randomDouble(random, -180, 180)); // Longitude
        location.add(randomDouble(random, -90, 90));   // Latitude
        return location;
    }

    private String randomCountryCode(Random random) {
        return randomElement(COUNTRY_CODES, random);
    }
}
