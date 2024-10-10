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

public class HttpLogs implements Workload {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String[] HTTP_METHODS = { "GET", "POST", "PUT", "DELETE" };
    private static final String[] URLS = { "/home", "/login", "/search", "/api/data", "/contact" };
    private static final int[] RESPONSE_CODES = { 200, 201, 400, 401, 403, 404, 500 };

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

    @Override
    public ObjectNode createIndex(ObjectNode defaultSettings) {
        var properties = mapper.createObjectNode();
        properties.set("logId", createField("integer"));
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

    @Override
    public Stream<ObjectNode> createDocs(int numDocs) {
        var random = new Random(1L);
        var currentTime = System.currentTimeMillis();

        return IntStream.range(0, numDocs)
            .mapToObj(i -> {
                ObjectNode doc = mapper.createObjectNode();
                doc.put("logId", i + 1000);
                doc.put("clientip", randomIpAddress(random));
                doc.put("@timestamp", randomTime(currentTime, random));
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
