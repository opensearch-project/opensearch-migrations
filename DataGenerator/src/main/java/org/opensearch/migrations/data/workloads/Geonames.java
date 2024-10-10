package org.opensearch.migrations.data.workloads;

import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.opensearch.migrations.data.FieldBuilders.createField;
import static org.opensearch.migrations.data.FieldBuilders.createFieldTextRawKeyword;
import static org.opensearch.migrations.data.RandomDataBuilders.randomDouble;
import static org.opensearch.migrations.data.RandomDataBuilders.randomElement;

/**
 * Workload based off of Geonames
 * https://github.com/opensearch-project/opensearch-benchmark-workloads/tree/main/geonames
 */
public class Geonames implements Workload {

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
        var properties = mapper.createObjectNode();
        properties.set("geonameid", createField("long"));
        properties.set("name", createFieldTextRawKeyword());
        properties.set("asciiname", createFieldTextRawKeyword());
        properties.set("alternatenames", createFieldTextRawKeyword());
        properties.set("feature_class", createFieldTextRawKeyword());
        properties.set("feature_code", createFieldTextRawKeyword());
        properties.set("cc2", createFieldTextRawKeyword());
        properties.set("admin1_code", createFieldTextRawKeyword());
        properties.set("admin2_code", createFieldTextRawKeyword());
        properties.set("admin3_code", createFieldTextRawKeyword());
        properties.set("admin4_code", createFieldTextRawKeyword());
        properties.set("elevation", createField("integer"));
        properties.set("population", createField("long"));
        properties.set("dem", createFieldTextRawKeyword());
        properties.set("timezone", createFieldTextRawKeyword());
        properties.set("location", createField("geo_point"));

        var countryCodeField = createFieldTextRawKeyword();
        countryCodeField.put("fielddata", true);
        properties.set("country_code", countryCodeField);

        var mappings = mapper.createObjectNode();
        mappings.put("dynamic", "strict");
        mappings.set("properties", properties);

        var index = mapper.createObjectNode();
        index.set("mappings", mappings);
        index.set("settings", defaultSettings);
        return index;
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
                var doc = mapper.createObjectNode();
                doc.put("geonameid", i + 1000);
                doc.put("name", "City" + (i + 1));
                doc.put("asciiname", "City" + (i + 1));
                doc.put("alternatenames", "City" + (i + 1));
                doc.put("feature_class", "FCl" + (i + 1));
                doc.put("feature_code", "FCo" + (i + 1));
                doc.put("country_code", randomCountryCode(random));
                doc.put("cc2", "cc2" + (i + 1));
                doc.put("admin1_code", "admin" + (i + 1));
                doc.put("population", random.nextInt(1000));
                doc.put("dem", random.nextInt(1000) + "");
                doc.put("timezone", "TZ" + (i + 1));
                doc.set("location", randomLocation(random));
                return doc;
            }
        );
    }

    private static ArrayNode randomLocation(Random random) {
        var location = mapper.createArrayNode();
        location.add(randomDouble(random, -180, 180)); // Longitude
        location.add(randomDouble(random, -90, 90));   // Latitude
        return location;
    }

    private static String randomCountryCode(Random random) {
        return randomElement(COUNTRY_CODES, random);
    }
}
