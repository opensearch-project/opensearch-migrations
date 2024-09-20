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

@UtilityClass
public class HttpLogs {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String[] HTTP_METHODS = {"GET", "POST", "PUT", "DELETE"};
    private static final String[] URLS = {"/home", "/login", "/search", "/api/data", "/contact"};
    private static final int[] RESPONSE_CODES = {200, 201, 400, 401, 403, 404, 500};
    private static final int ONE_DAY_IN_MILLIS = 24 * 60 * 60 * 1000;

    public static ObjectNode generateHttpLogIndex() {
        var index = mapper.createObjectNode();
        var mappings = mapper.createObjectNode();
        var properties = mapper.createObjectNode();
        
        properties.set("logId", createField("integer"));
        properties.set("ip_address", createField("ip"));
        properties.set("timestamp", createField("date"));
        properties.set("method", createField("keyword"));
        properties.set("url", createField("keyword"));
        properties.set("response_code", createField("integer"));
        properties.set("response_time", createField("float"));
        
        mappings.set("properties", properties);
        index.set("mappings", mappings);
        return index;
    }

    public static Stream<ObjectNode> generateHttpLogDocs(int numDocs) {
        var random = new Random(1L);
        var sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        var currentTime = System.currentTimeMillis();

        return IntStream.range(0, numDocs)
            .mapToObj(i -> {
                ObjectNode doc = mapper.createObjectNode();
                doc.put("logId", i + 1000);
                doc.put("ip_address", randomIpAddress(random));
                doc.put("timestamp", sdf.format(randomTimeWithin24Hours(currentTime, random)));
                doc.put("method", randomHttpMethod(random));
                doc.put("url", randomUrl(random));
                doc.put("response_code", randomResponseCode(random));
                doc.put("response_time", randomResponseTime(random));
                return doc;
            }
        );
    }

    private static String randomIpAddress(Random random) {
        return random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(256);
    }

    private static Date randomTimeWithin24Hours(long timeFrom, Random random) {
        return new Date(timeFrom - random.nextInt(ONE_DAY_IN_MILLIS));

    }

    private static String randomHttpMethod(Random random) {
        return HTTP_METHODS[random.nextInt(HTTP_METHODS.length)];
    }

    private static String randomUrl(Random random) {
        return URLS[random.nextInt(URLS.length)];
    }

    private static int randomResponseCode(Random random) {
        return RESPONSE_CODES[random.nextInt(RESPONSE_CODES.length)];
    }

    private static double randomResponseTime(Random random) {
        return 0.1 + (5 * random.nextDouble()); // Response time between 0.1 and 5.0 seconds
    }
}
