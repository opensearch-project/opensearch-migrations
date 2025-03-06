package org.opensearch.migrations.replay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.datahandlers.PayloadAccessFaultingMap;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonRequestWithFaultingPayload;
import org.opensearch.migrations.replay.datahandlers.http.StrictCaseInsensitiveHttpHeadersMap;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;
import org.opensearch.migrations.transform.TransformationLoader;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class TypeMappingTransformationTest {

    /**
     * Provides test cases for bulk transformation.
     *
     * Each argument consists of:
     * - inputPath: the original HTTP path (determines the match length)
     * - expectedUri: the expected transformed URI after removing defaults and applying conversion
     * - expectedFirstIndex: the expected _index value in the first NDJSON command after transformation
     *
     * Cases:
     * 1. No default index in URI: inputPath="/_bulk"
     * 2. Default index provided: inputPath="/btc-rfs/_bulk"
     * 3. Default index and type provided: inputPath="/btc-rfs/sometype/_bulk"
     */
    static Stream<Arguments> bulkTransformationTestCases() {
        return Stream.of(
                Arguments.of("/_bulk", "/_bulk", "btc-rfs_transformed"),
                Arguments.of("/btc-rfs/_bulk", "/btc-rfs_transformed/_bulk", "btc-rfs_transformed"),
                Arguments.of("/btc-rfs/sometype/_bulk", "/btc-rfs_transformed-sometype/_bulk", "btc-rfs_transformed-sometype")
        );
    }

    @ParameterizedTest
    @MethodSource("bulkTransformationTestCases")
    public void testBulkTransformation(String inputPath, String expectedUri, String expectedFirstIndex) {
        // Setup HTTP message with given inputPath.
        // The path drives the defaultSourceIndex and defaultType for the transformation.
        var headers = new StrictCaseInsensitiveHttpHeadersMap();
        headers.put("content-type", List.of("application/json"));
        var httpMessage = new HttpJsonRequestWithFaultingPayload(headers);
        httpMessage.setPayloadFaultMap(new PayloadAccessFaultingMap(headers));
        httpMessage.setPath(inputPath);
        httpMessage.setMethod("POST");

        // Prepare a sample NDJSON payload.
        // The first command is the index command with _index "btc-rfs"
        // which should be transformed to "btc-rfs_transformed" per the regex mapping.
        List<Map<String, Object>> dataList = new ArrayList<>();

        Map<String, Object> indexCommand = new HashMap<>();
        indexCommand.put("index", Map.of("_id", "sampleId", "_index", "btc-rfs"));
        dataList.add(indexCommand);

        // Add a dummy document (transaction) which remains unchanged.
        Map<String, Object> transactionDoc = new HashMap<>();
        transactionDoc.put("dummyField", "dummyValue");
        dataList.add(transactionDoc);

        httpMessage.payload().put(JsonKeysForHttpMessage.INLINED_NDJSON_BODIES_DOCUMENT_KEY, dataList);

        // Create transformer with configuration.
        // The regex mapping converts any index starting with "btc" to have a "_transformed" suffix.
        var transformer = new TransformationLoader().getTransformerFactoryLoader(
                null,
                null,
                "[{ \"TypeMappingSanitizationTransformerProvider\": { " +
                        "  \"sourceProperties\": { " +
                        "    \"version\": { " +
                        "      \"major\": 7, " +
                        "      \"minor\": 10 " +
                        "    } " +
                        "  }, " +
                        "  \"regexMappings\": [ " +
                        "    { " +
                        "      \"sourceIndexPattern\": \"(btc.*)\", " +
                        "      \"sourceTypePattern\":  \"(sometype)\", " +
                        "      \"targetIndexPattern\": \"$1_transformed-$2\" " +
                        "    }, " +
                        "    { " +
                        "      \"sourceIndexPattern\": \"(btc.*)\", " +
                        "      \"sourceTypePattern\":  \"_doc\", " +
                        "      \"targetIndexPattern\": \"$1_transformed\" " +
                        "    } " +
                        "  ] " +
                        "} }] "
        );

        // Transform the HTTP message
        var rval = (Map<String, Object>) transformer.transformJson(httpMessage);

        // Extract the transformed URI and the first NDJSON command's _index value.
        var transformedPayload = (Map<String, Object>) rval.get(JsonKeysForHttpMessage.PAYLOAD_KEY);
        var ndjsonList = (List<Map<String, Map<String, Object>>>) transformedPayload.get(JsonKeysForHttpMessage.INLINED_NDJSON_BODIES_DOCUMENT_KEY);
        String firstTargetIndex = (String) ndjsonList.get(0).get("index").get("_index");
        String transformedPath = (String) rval.get(JsonKeysForHttpMessage.URI_KEY);

        // Validate that the transformed URI and first index match the expected values.
        Assertions.assertEquals(expectedUri, transformedPath);
        Assertions.assertEquals(expectedFirstIndex, firstTargetIndex);
    }
}
