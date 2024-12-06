package org.opensearch.migrations.data.workloads;

import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.opensearch.migrations.data.IFieldCreator;
import org.opensearch.migrations.data.IRandomDataBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Workload based off of http_logs
 * https://github.com/opensearch-project/opensearch-benchmark-workloads/tree/main/http_logs
 */
public class HttpLogs implements Workload, IFieldCreator, IRandomDataBuilders {

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
        return mapper.createObjectNode()
            .<ObjectNode>set("mappings", mapper.createObjectNode()
                .<ObjectNode>put("dynamic", "strict")
                .<ObjectNode>set("properties",  mapper.createObjectNode()
                    .<ObjectNode>set("@timestamp", fieldDate()
                        .put("format", "strict_date_optional_time||epoch_second"))
                    .<ObjectNode>set("message", fieldKeyword()
                        .<ObjectNode>put("index", false)
                        .<ObjectNode>put("doc_values", false))
                    .<ObjectNode>set("clientip", fieldIP())
                    .<ObjectNode>set("request",
                        ((ObjectNode) fieldRawTextKeyword().get("fields").get("raw"))
                            .put("ignore_above", 256))
                    .<ObjectNode>set("status", fieldInt())
                    .<ObjectNode>set("size", fieldInt())
                    .<ObjectNode>set("geoip", mapper.createObjectNode()
                        .set("properties", mapper.createObjectNode()
                            .<ObjectNode>set("country_name", fieldKeyword())
                            .<ObjectNode>set("city_name", fieldKeyword())
                            .<ObjectNode>set("location", fieldGeoPoint())))))
            .<ObjectNode>set("settings", defaultSettings);
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
                long randomTime = randomTime(currentTime, random);
                return mapper.createObjectNode()
                    .put("@timestamp", randomTime)
                    .put("clientip", randomIpAddress(random))
                    .put("request", randomRequest(random))
                    .put("status", randomStatus(random))
                    .put("size", randomResponseSize(random));
            }
        );
    }

    private static String randomIpAddress(Random random) {
        return random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(256);
    }

    private String randomHttpMethod(Random random) {
        return randomElement(HTTP_METHODS, random);
    }

    private String randomRequest(Random random) {
        return randomHttpMethod(random) + " " + randomUrl(random) + " HTTP/1.0";
    }

    private String randomUrl(Random random) {
        return randomElement(URLS, random);
    }

    private int randomStatus(Random random) {
        return randomElement(RESPONSE_CODES, random);
    }

    private int randomResponseSize(Random random) {
        return random.nextInt(50 * 1024 * 1024);
    }
}
