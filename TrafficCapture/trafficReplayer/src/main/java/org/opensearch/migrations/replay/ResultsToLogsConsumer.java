package org.opensearch.migrations.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datatypes.UniqueSourceRequestKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.BiConsumer;

@Slf4j
public class ResultsToLogsConsumer implements BiConsumer<SourceTargetCaptureTuple, ParsedHttpMessagesAsDicts> {
    public static final String OUTPUT_TUPLE_JSON_LOGGER = "OutputTupleJsonLogger";
    public static final String TRANSACTION_SUMMARY_LOGGER = "TransactionSummaryLogger";
    private static final ObjectMapper PLAIN_MAPPER = new ObjectMapper();

    Logger tupleLogger = LoggerFactory.getLogger(OUTPUT_TUPLE_JSON_LOGGER);
    Logger progressLogger = makeTransactionSummaryLogger();

    // set this up so that the preamble prints out once, right after we have a logger
    // if it's configured to output at all
    private static Logger makeTransactionSummaryLogger() {
        var logger = LoggerFactory.getLogger(TRANSACTION_SUMMARY_LOGGER);
        logger.atInfo().setMessage(()->getTransactionSummaryStringPreamble()).log();
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
        parsed.targetResponseOp.ifPresent(r -> tupleMap.put("targetResponse", r));

        tupleMap.put("connectionId", formatUniqueRequestKey(tuple.uniqueRequestKey));
        Optional.ofNullable(tuple.errorCause).ifPresent(e -> tupleMap.put("error", e.toString()));

        return tupleMap;
    }

    /**
     * Writes a tuple object to an output stream as a JSON object.
     * The JSON tuple is output on one line, and has several objects: "sourceRequest", "sourceResponse",
     * "targetRequest", and "targetResponse". The "connectionId" is also included to aid in debugging.
     * An example of the format is below.
     * <p>
     * {
     * "sourceRequest": {
     * "Request-URI": XYZ,
     * "Method": XYZ,
     * "HTTP-Version": XYZ
     * "body": XYZ,
     * "header-1": XYZ,
     * "header-2": XYZ
     * },
     * "targetRequest": {
     * "Request-URI": XYZ,
     * "Method": XYZ,
     * "HTTP-Version": XYZ
     * "body": XYZ,
     * "header-1": XYZ,
     * "header-2": XYZ
     * },
     * "sourceResponse": {
     * "HTTP-Version": ABC,
     * "Status-Code": ABC,
     * "Reason-Phrase": ABC,
     * "response_time_ms": 123,
     * "body": ABC,
     * "header-1": ABC
     * },
     * "targetResponse": {
     * "HTTP-Version": ABC,
     * "Status-Code": ABC,
     * "Reason-Phrase": ABC,
     * "response_time_ms": 123,
     * "body": ABC,
     * "header-2": ABC
     * },
     * "connectionId": "0242acfffe1d0008-0000000c-00000003-0745a19f7c3c5fc9-121001ff.0"
     * }
     *
     * @param tuple the RequestResponseResponseTriple object to be converted into json and written to the stream.
     */
    public void accept(SourceTargetCaptureTuple tuple, ParsedHttpMessagesAsDicts parsedMessages) {
        progressLogger.atInfo().setMessage(()->toTransactionSummaryString(tuple, parsedMessages)).log();
        tupleLogger.atInfo().setMessage(() -> {
            try {
                return PLAIN_MAPPER.writeValueAsString(toJSONObject(tuple, parsedMessages));
            } catch (Exception e) {
                throw Lombok.sneakyThrow(e);
            }
        }).log();
    }

    public static String getTransactionSummaryStringPreamble() {
        return new StringJoiner(", ")
                .add("SOURCE_STATUS_CODE")
                .add("TARGET_STATUS_CODE")
                .add("SOURCE_LATENCY")
                .add("TARGET_LATENCY")
                .add("REQUEST_ID")
                .toString();
    }

    public static String toTransactionSummaryString(SourceTargetCaptureTuple tuple, ParsedHttpMessagesAsDicts parsed) {
        final String MISSING_STR = "-";
        var s = parsed.sourceResponseOp;
        var t = parsed.targetResponseOp;
        return new StringJoiner(", ")
                // SOURCE_STATUS_CODE
                .add(s.map(r->""+r.get(ParsedHttpMessagesAsDicts.STATUS_CODE_KEY)).orElse(MISSING_STR))
                // TARGET_STATUS_CODE
                .add(t.map(r->""+r.get(ParsedHttpMessagesAsDicts.STATUS_CODE_KEY)).orElse(MISSING_STR))
                // SOURCE_LATENCY
                .add(s.map(r->""+r.get(ParsedHttpMessagesAsDicts.RESPONSE_TIME_MS_KEY)).orElse(MISSING_STR))
                // TARGET_LATENCY
                .add(t.map(r->""+r.get(ParsedHttpMessagesAsDicts.RESPONSE_TIME_MS_KEY)).orElse(MISSING_STR))
                // REQUEST_ID
                .add(formatUniqueRequestKey(tuple.uniqueRequestKey))
                .toString();
    }

}
