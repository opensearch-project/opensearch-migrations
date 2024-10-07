package org.opensearch.migrations.data;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.experimental.UtilityClass;

import static org.opensearch.migrations.data.GeneratedData.createField;
import static org.opensearch.migrations.data.GeneratedData.createFieldTextRawKeyword;

@UtilityClass
public class HttpLogs {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String[] HTTP_METHODS = {"GET", "POST", "PUT", "DELETE"};
    private static final String[] URLS = {"/home", "/login", "/search", "/api/data", "/contact"};
    private static final int[] RESPONSE_CODES = {200, 201, 400, 401, 403, 404, 500};
    private static final int ONE_DAY_IN_MILLIS = 24 * 60 * 60 * 1000;

    public static ObjectNode generateHttpLogIndex() {
        var properties = mapper.createObjectNode();
        properties.set("logId", createField("integer"));
        var timestamp = createField("date");
        timestamp.put("format", "strict_date_optional_time||epoch_second")
        properties.set("@timestamp", timestamp);
        var message = createField("keyword");
        message.put("index", false);
        message.put("doc_values", false);
        properties.set("message", message);
        properties.set("clientip", createField("ip"));
        var request = createFieldTextRawKeyword();
        var requestRaw = (ObjectNode)request.get("fields").get("raw");
        requestRaw.put("ignore_above", 256);
        properties.set("request", request);
        properties.set("status", createField("integer"));
        properties.set("integer", createField("integer"));
        var geoip = mapper.createObjectNode();
        var geoipProps = mapper.createObjectNode();
        geoip.set("properties", geoipProps);
        geoipProps.set("country_name", createField("keyword"));
        geoipProps.set("city_name", createField("keyword"));
        geoipProps.set("location", createField("geo_point"));
        properties.set("geoip", geoip);
        
        var mappings = mapper.createObjectNode();
        mappings.put("dynamic", "strict");
        mappings.set("properties", properties);

        var index = mapper.createObjectNode();
        index.set("mappings", mappings);
        return index;
    }

    public static Stream<ObjectNode> generateHttpLogDocs(int numDocs) {
        var random = new Random(1L);
        var currentTime = System.currentTimeMillis();

        return IntStream.range(0, numDocs)
            .mapToObj(i -> {
                ObjectNode doc = mapper.createObjectNode();
                doc.put("logId", i + 1000);
                doc.put("clientip", randomIpAddress(random));
                doc.put("@timestamp", randomTimeWithin24Hours(currentTime, random));
                doc.put("request", randomHttpMethod(random) + " " + randomUrl(random) + " HTTP/1.0");
                doc.put("status", randomStatus(random));
                doc.put("size", randomResponseSize(random));
                return doc;
            }
        );
    }

    private static String randomIpAddress(Random random) {
        return random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(256);
    }

    private static long randomTimeWithin24Hours(long timeFrom, Random random) {
        return timeFrom - random.nextInt(ONE_DAY_IN_MILLIS);
    }

    private static String randomHttpMethod(Random random) {
        return HTTP_METHODS[random.nextInt(HTTP_METHODS.length)];
    }

    private static String randomUrl(Random random) {
        return URLS[random.nextInt(URLS.length)];
    }

    private static int randomStatus(Random random) {
        return RESPONSE_CODES[random.nextInt(RESPONSE_CODES.length)];
    }

    private static int randomResponseSize(Random random) {
        return random.nextInt(50 * 1024 * 1024);
    }
}
