package org.opensearch.migrations.data;

import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.opensearch.migrations.data.GeneratedData.createField;
import static org.opensearch.migrations.data.GeneratedData.createFieldTextRawKeyword;

public class Geonames {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String[] countryCodes = {"US", "DE", "FR", "GB", "CN", "IN", "BR"};

    public static ObjectNode generateGeonameIndex() {
        var properties = mapper.createObjectNode();
        
        properties.set("geonameId", createField("long"));
        properties.set("name", createFieldTextRawKeyword());
        properties.set("asciiname", createFieldTextRawKeyword());
        properties.set("alternatenames", createFieldTextRawKeyword());
        properties.set("feature_class", createFieldTextRawKeyword());
        properties.set("feature_code", createFieldTextRawKeyword());
        properties.set("cc2", createFieldTextRawKeyword());
        properties.set("admin1_code", createFieldTextRawKeyword());
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
        return index;
    }

    public static Stream<ObjectNode> generateGeoNameDocs(int numDocs) {
        var random = new Random(1L);

        return IntStream.range(0, numDocs)
            .mapToObj(i -> {
                var doc = mapper.createObjectNode();
                doc.put("geonameId", i + 1000);
                doc.put("name", "City" + (i + 1));
                doc.put("asciiname", "City" + (i + 1));
                doc.put("alternatenames", "City" + (i + 1));
                doc.put("feature_class", "FCl" + (i + 1));
                doc.put("feature_code", "FCo" + (i + 1));
                doc.put("cc2", "cc2" + (i + 1));
                doc.put("admin1_code", "admin" + (i + 1));
                doc.put("population", random.nextInt(1000));
                doc.put("dem", random.nextInt(1000)+"");
                doc.put("timezone", "TZ"+ (i + 1));
                var location = mapper.createArrayNode();
                location.add(randomLatitude(random));
                location.add(randomLongitude(random));
                doc.set("location", location);
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
