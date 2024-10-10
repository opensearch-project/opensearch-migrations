package org.opensearch.migrations.data.workloads;

import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.opensearch.migrations.data.FieldBuilders.createField;
import static org.opensearch.migrations.data.FieldBuilders.createFieldTextRawKeyword;
import static org.opensearch.migrations.data.RandomDataBuilders.randomElement;
import static org.opensearch.migrations.data.RandomDataBuilders.randomTime;

/**
 * Workload based off of http_logs
 * https://github.com/opensearch-project/opensearch-benchmark-workloads/tree/main/http_logs
 */
public class HttpLogs implements Workload {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String[] HTTP_METHODS = { "GET", "POST", "PUT", "DELETE" };
    private static final int[] RESPONSE_CODES = { 200, 201, 400, 401, 403, 404, 500 };
    private static final String[] URLS = {
        "/home", "/login", "/search", "/api/data", "/contact"
    };

    @Override
    public List<String> indexNames() {
        return List.of(
            "logs-181998",
            "logs-191998",
            "logs-201998",
            "logs-211998",
            "logs-221998",
            "logs-231998",
            "logs-241998"
        );
    }

    /**
     * Mirroring index configuration from 
     * https://github.com/opensearch-project/opensearch-benchmark-workloads/blob/main/http_logs/index.json
     */
    @Override
    public ObjectNode createIndex(ObjectNode defaultSettings) {
        var properties = mapper.createObjectNode();
        var timestamp = createField("date");
        timestamp.put("format", "strict_date_optional_time||epoch_second");
        properties.set("@timestamp", timestamp);
        var message = createField("keyword");
        message.put("index", false);
        message.put("doc_values", false);
        properties.set("message", message);
        properties.set("clientip", createField("ip"));
        var request = createFieldTextRawKeyword();
        var requestRaw = (ObjectNode) request.get("fields").get("raw");
        requestRaw.put("ignore_above", 256);
        properties.set("request", request);
        properties.set("status", createField("integer"));
        properties.set("size", createField("integer"));
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
        index.set("settings", defaultSettings);
        return index;
    }

    /**
     * Example generated document:
     {
         "@timestamp": 1728504268181,
         "clientip": "106.171.39.19",
         "request": "POST /contact HTTP/1.0",
         "status": 403,
         "size": 16672893
     }
     */
    @Override
    public Stream<ObjectNode> createDocs(int numDocs) {
        var currentTime = System.currentTimeMillis();

        return IntStream.range(0, numDocs)
            .mapToObj(i -> {
                var random = new Random(i);
                ObjectNode doc = mapper.createObjectNode();
                doc.put("@timestamp", randomTime(currentTime, random));
                doc.put("clientip", randomIpAddress(random));
                doc.put("request", randomRequest(random));
                doc.put("status", randomStatus(random));
                doc.put("size", randomResponseSize(random));
                return doc;
            }
        );
    }

    private static String randomIpAddress(Random random) {
        return random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(256);
    }

    private static String randomHttpMethod(Random random) {
        return randomElement(HTTP_METHODS, random);
    }

    private static String randomRequest(Random random) {
        return randomHttpMethod(random) + " " + randomUrl(random) + " HTTP/1.0";
    }

    private static String randomUrl(Random random) {
        return randomElement(URLS, random);
    }

    private static int randomStatus(Random random) {
        return randomElement(RESPONSE_CODES, random);
    }

    private static int randomResponseSize(Random random) {
        return random.nextInt(50 * 1024 * 1024);
    }
}
