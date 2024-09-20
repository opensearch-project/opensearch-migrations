package org.opensearch.migrations.data;

import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.opensearch.migrations.data.GeneratedData.createField;

public class Geonames {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String[] countryCodes = {"US", "DE", "FR", "GB", "CN", "IN", "BR"};

    public static ObjectNode generateGeonameIndex() {
        var index = mapper.createObjectNode();
        var mappings = mapper.createObjectNode();
        var properties = mapper.createObjectNode();
        
        properties.set("geonameId", createField("integer"));
        properties.set("name", createField("text"));
        properties.set("latitude", createField("float"));
        properties.set("longitude", createField("float"));
        properties.set("countryCode", createField("keyword"));
        
        mappings.set("properties", properties);
        index.set("mappings", mappings);
        return index;
    }

    public static Stream<ObjectNode> generateGeoNameDocs(int numDocs) {
        var random = new Random(1L);

        return IntStream.range(0, numDocs)
            .mapToObj(i -> {
                var doc = mapper.createObjectNode();
                doc.put("geonameId", i + 1000);
                doc.put("name", "City" + (i + 1));
                doc.put("latitude", randomLatitude(random));
                doc.put("longitude", randomLongitude(random));
                doc.put("countryCode", randomCountryCode(random));
                return doc;
            }
        );
    }

    private static double randomLatitude(Random random) {
        return -90 + (180 * random.nextDouble()); // Latitude range: -90 to +90
    }

    private static double randomLongitude(Random random) {
        return -180 + (360 * random.nextDouble()); // Longitude range: -180 to +180
    }

    private static String randomCountryCode(Random random) {
        return countryCodes[random.nextInt(countryCodes.length)];
    }
}
