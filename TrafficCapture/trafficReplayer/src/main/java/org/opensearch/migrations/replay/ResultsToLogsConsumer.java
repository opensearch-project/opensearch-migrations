package org.opensearch.migrations.replay;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.datatypes.UniqueSourceRequestKey;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.TransformationLoader;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Slf4j
public class ResultsToLogsConsumer implements BiConsumer<SourceTargetCaptureTuple, ParsedHttpMessagesAsDicts> {
    public static final String OUTPUT_TUPLE_JSON_LOGGER = "OutputTupleJsonLogger";
    public static final String TRANSACTION_SUMMARY_LOGGER = "TransactionSummaryLogger";
    private static final String MISSING_STR = "-";
    private static final ObjectMapper PLAIN_MAPPER = new ObjectMapper();
    private static final IJsonTransformer NOOP_JSON_TRANSFORMER = new TransformationLoader().getTransformerFactoryLoader(
        null, null, "NoopTransformerProvider");

    private final Logger tupleLogger;
    private final Logger progressLogger;
    private final IJsonTransformer tupleTransformer;

    private final AtomicInteger tupleCounter;

    public ResultsToLogsConsumer(Logger tupleLogger, Logger progressLogger, IJsonTransformer tupleTransformer) {
        this.tupleLogger = tupleLogger != null ? tupleLogger : LoggerFactory.getLogger(OUTPUT_TUPLE_JSON_LOGGER);
        this.progressLogger = progressLogger != null ? progressLogger : makeTransactionSummaryLogger();
        tupleCounter = new AtomicInteger();
        this.tupleTransformer = tupleTransformer != null ? tupleTransformer : NOOP_JSON_TRANSFORMER ;
    }

    // set this up so that the preamble prints out once, right after we have a logger
    // if it's configured to output at all
    private static Logger makeTransactionSummaryLogger() {
        var logger = LoggerFactory.getLogger(TRANSACTION_SUMMARY_LOGGER);
        logger.atDebug().setMessage("{}").addArgument(ResultsToLogsConsumer::getTransactionSummaryStringPreamble).log();
        return logger;
    }

    private static String formatUniqueRequestKey(UniqueSourceRequestKey k) {
        return k.getTrafficStreamKey().getConnectionId() + "." + k.getSourceRequestIndex();
    }

    private Map<String, Object> toJSONObject(SourceTargetCaptureTuple tuple, ParsedHttpMessagesAsDicts parsed) {
        var tupleMap = new LinkedHashMap<String, Object>();

        parsed.sourceRequestOp.ifPresent(r -> tupleMap.put("sourceRequest", r));
        parsed.sourceResponseOp.ifPresent(r -> tupleMap.put("sourceResponse", r));
        parsed.targetRequestOp.ifPresent(r -> tupleMap.put("targetRequest", r));
        tupleMap.put("targetResponses", parsed.targetResponseList);

        tupleMap.put("connectionId", formatUniqueRequestKey(tuple.getRequestKey()));
        Optional.ofNullable(tuple.topLevelErrorCause).ifPresent(e -> tupleMap.put("error", e.toString()));
        tupleMap.put("numRequests",  tuple.responseList.size());
        tupleMap.put("numErrors",  tuple.responseList.stream().filter(r->r.errorCause!=null).count());

        return tupleMap;
    }

    /**
     * Writes a tuple object to an output stream as a JSON object.
     * The JSON tuple is output on one line, and has several objects: "sourceRequest", "sourceResponse",
     * "targetRequest", and "targetResponses". The "connectionId", "numRequests", and "numErrors" are also included to aid in debugging.
     * An example of the format is below to highlight the different possibilities in the format, not to convey the representation for an actual http exchange.
     * <p>
     * {
     *   "sourceRequest": {
     *     "Request-URI": "/api/v1/resource",
     *     "Method": "POST",
     *     "HTTP-Version": "HTTP/1.1",
     *     "header-1": "Content-Type: application/json",
     *     "header-2": "Authorization: Bearer token",
     *     "payload": {
     *       "inlinedJsonBody": {
     *         "key1": "value1",
     *         "key2": "value2"
     *       }
     *     }
     *   },
     *   "targetRequest": {
     *     "Request-URI": "/api/v1/target",
     *     "Method": "GET",
     *     "HTTP-Version": "HTTP/1.1",
     *     "header-1": "Accept: application/json",
     *     "header-2": "Authorization: Bearer token",
     *     "payload": {
     *       "inlinedBinaryBody": ByteBuf{...}
     *     }
     *   },
     *   "sourceResponse": {
     *     "response_time_ms": 150,
     *     "HTTP-Version": "HTTP/1.1",
     *     "Status-Code": 200,
     *     "Reason-Phrase": "OK",
     *     "header-1": "Content-Type: text/plain",
     *     "header-2": "Cache-Control: no-cache",
     *     "payload": {
     *       "inlinedTextBody": "The quick brown fox jumped over the lazy dog\r"
     *       }
     *     }
     *   },
     *   "targetResponses": [{
     *     "response_time_ms": 100,
     *     "HTTP-Version": "HTTP/1.1",
     *     "Status-Code": 201,
     *     "Reason-Phrase": "Created",
     *     "header-1": "Content-Type: application/json",
     *     "payload": {
     *       "inlinedJsonSequenceBodies": [
     *         {"sequenceKey1": "sequenceValue1"},
     *         {"sequenceKey2": "sequenceValue2"}
     *       ]
     *     }
     *   }],
     *   "connectionId": "conn-12345",
     *   "numRequests": 5,
     *   "numErrors": 1,
     *   "error": "Request timed out"
     * }
     *
     * @param tuple the RequestResponseResponseTriple object to be converted into json and written to the stream.
     */
    public void accept(SourceTargetCaptureTuple tuple, ParsedHttpMessagesAsDicts parsedMessages) {
        final var index = tupleCounter.getAndIncrement();
        progressLogger.atInfo().setMessage("{}")
            .addArgument(() -> toTransactionSummaryString(index, tuple, parsedMessages)).log();
        if (tupleLogger.isInfoEnabled()) {
            try {
                var originalTuple = toJSONObject(tuple, parsedMessages);
                var transformedTuple = tupleTransformer.transformJson(originalTuple);
                var tupleString = PLAIN_MAPPER.writeValueAsString(transformedTuple);
                tupleLogger.atInfo().setMessage("{}").addArgument(tupleString).log();
            } catch (Exception e) {
                log.atError().setCause(e).setMessage("Exception converting tuple to string").log();
                tupleLogger.atInfo().setMessage("{ \"error\":\"{}\" }").addArgument(e::getMessage).log();
                throw Lombok.sneakyThrow(e);
            }
        }
    }

    public static String getTransactionSummaryStringPreamble() {
        return new StringJoiner(", ").add("#")
            .add("REQUEST_ID")
            .add("ORIGINAL_TIMESTAMP")
            .add("SOURCE_REQUEST_SIZE_BYTES/TARGET_REQUEST_SIZE_BYTES")
            .add("SOURCE_STATUS_CODE/TARGET_STATUS_CODE...")
            .add("SOURCE_RESPONSE_SIZE_BYTES/TARGET_RESPONSE_SIZE_BYTES...")
            .add("SOURCE_LATENCY_MS/TARGET_LATENCY_MS...")
            .toString();
    }

    public static String toTransactionSummaryString(
        int index,
        SourceTargetCaptureTuple tuple,
        ParsedHttpMessagesAsDicts parsed
    ) {
        var sourceResponse = parsed.sourceResponseOp;
        return new StringJoiner(", ").add(Integer.toString(index))
            // REQUEST_ID
            .add(formatUniqueRequestKey(tuple.getRequestKey()))
            // Original request timestamp
            .add(
                Optional.ofNullable(tuple.sourcePair)
                    .map(sp -> sp.requestData.getLastPacketTimestamp().toString())
                    .orElse(MISSING_STR)
            )
            // SOURCE/TARGET REQUEST_SIZE_BYTES
            .add(
                Optional.ofNullable(tuple.sourcePair)
                    .map(sp -> sp.requestData.stream().mapToInt(bArr -> bArr.length).sum() + "")
                    .orElse(MISSING_STR)
                    + "/"
                    + Optional.ofNullable(tuple.targetRequestData)
                        .map(
                            transformedPackets -> transformedPackets.streamUnretained()
                                .mapToInt(ByteBuf::readableBytes)
                                .sum()
                                + ""
                        )
                        .orElse(MISSING_STR)
            )
            // SOURCE/TARGET STATUS_CODE
            .add(
                sourceResponse.map(r -> "" + r.get(ParsedHttpMessagesAsDicts.STATUS_CODE_KEY)).orElse(MISSING_STR)
                    + "/" +
                    transformStreamToString(parsed.targetResponseList.stream(),
                        r -> "" + r.get(ParsedHttpMessagesAsDicts.STATUS_CODE_KEY))
            )
            // SOURCE/TARGET RESPONSE_SIZE_BYTES
            .add(
                Optional.ofNullable(tuple.sourcePair)
                    .flatMap(sp -> Optional.ofNullable(sp.responseData))
                    .map(rd -> rd.stream().mapToInt(bArr -> bArr.length).sum() + "")
                    .orElse(MISSING_STR)
                    + "/" +
                    transformStreamToString(tuple.responseList.stream(),
                                r -> r.targetResponseData.stream().mapToInt(bArr -> bArr.length).sum() + "")
            )
            // SOURCE/TARGET LATENCY
            .add(
                sourceResponse.map(r -> "" + r.get(ParsedHttpMessagesAsDicts.RESPONSE_TIME_MS_KEY)).orElse(MISSING_STR)
                    + "/" +
                    transformStreamToString(parsed.targetResponseList.stream(),
                        r -> "" + r.get(ParsedHttpMessagesAsDicts.RESPONSE_TIME_MS_KEY))
            )
            .toString();
    }

    private static <T> String transformStreamToString(Stream<T> stream, Function<T,String> mapFunction) {
        return Stream.of(stream
                .map(mapFunction)
                .collect(Collectors.joining(",")))
            .filter(Predicate.not(String::isEmpty))
            .findFirst()
            .orElse(MISSING_STR);
    }
}
