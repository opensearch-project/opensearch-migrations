package org.opensearch.migrations.replay;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.opensearch.migrations.replay.datatypes.UniqueSourceRequestKey;

import io.netty.buffer.ByteBuf;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Slf4j
public class ResultsToLogsConsumer implements BiConsumer<SourceTargetCaptureTuple, ParsedHttpMessagesAsDicts> {
    public static final String OUTPUT_TUPLE_JSON_LOGGER = "OutputTupleJsonLogger";
    public static final String TRANSACTION_SUMMARY_LOGGER = "TransactionSummaryLogger";
    private static final ObjectMapper PLAIN_MAPPER = new ObjectMapper();

    private final Logger tupleLogger;
    private final Logger progressLogger;
    private final AtomicInteger tupleCounter;

    public ResultsToLogsConsumer() {
        this(null, null);
    }

    public ResultsToLogsConsumer(Logger tupleLogger, Logger progressLogger) {
        this.tupleLogger = tupleLogger != null ? tupleLogger : LoggerFactory.getLogger(OUTPUT_TUPLE_JSON_LOGGER);
        this.progressLogger = progressLogger != null ? progressLogger : makeTransactionSummaryLogger();
        tupleCounter = new AtomicInteger();
    }

    // set this up so that the preamble prints out once, right after we have a logger
    // if it's configured to output at all
    private static Logger makeTransactionSummaryLogger() {
        var logger = LoggerFactory.getLogger(TRANSACTION_SUMMARY_LOGGER);
        logger.atInfo().setMessage("{}").addArgument(() -> getTransactionSummaryStringPreamble()).log();
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

        tupleMap.put("connectionId", formatUniqueRequestKey(tuple.getRequestKey()));
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
        final var index = tupleCounter.getAndIncrement();
        progressLogger.atInfo()
            .setMessage("{}")
            .addArgument(() -> toTransactionSummaryString(index, tuple, parsedMessages))
            .log();
        if (tupleLogger.isInfoEnabled()) {
            try {
                var tupleString = PLAIN_MAPPER.writeValueAsString(toJSONObject(tuple, parsedMessages));
                tupleLogger.atInfo().setMessage("{}").addArgument(() -> tupleString).log();
            } catch (Exception e) {
                log.atError().setMessage("Exception converting tuple to string").setCause(e).log();
                tupleLogger.atInfo().setMessage("{}").addArgument("{ \"error\":\"" + e.getMessage() + "\" }").log();
                throw Lombok.sneakyThrow(e);
            }
        }
    }

    public static String getTransactionSummaryStringPreamble() {
        return new StringJoiner(", ").add("#")
            .add("REQUEST_ID")
            .add("ORIGINAL_TIMESTAMP")
            .add("SOURCE_STATUS_CODE/TARGET_STATUS_CODE")
            .add("SOURCE_REQUEST_SIZE_BYTES/TARGET_REQUEST_SIZE_BYTES")
            .add("SOURCE_RESPONSE_SIZE_BYTES/TARGET_RESPONSE_SIZE_BYTES")
            .add("SOURCE_LATENCY_MS/TARGET_LATENCY_MS")
            .toString();
    }

    public static String toTransactionSummaryString(
        int index,
        SourceTargetCaptureTuple tuple,
        ParsedHttpMessagesAsDicts parsed
    ) {
        final String MISSING_STR = "-";
        var s = parsed.sourceResponseOp;
        var t = parsed.targetResponseOp;
        return new StringJoiner(", ").add(Integer.toString(index))
            // REQUEST_ID
            .add(formatUniqueRequestKey(tuple.getRequestKey()))
            // Original request timestamp
            .add(
                Optional.ofNullable(tuple.sourcePair)
                    .map(sp -> sp.requestData.getLastPacketTimestamp().toString())
                    .orElse(MISSING_STR)
            )
            // SOURCE/TARGET STATUS_CODE
            .add(
                s.map(r -> "" + r.get(ParsedHttpMessagesAsDicts.STATUS_CODE_KEY)).orElse(MISSING_STR)
                    + "/"
                    + t.map(r -> "" + r.get(ParsedHttpMessagesAsDicts.STATUS_CODE_KEY)).orElse(MISSING_STR)
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
            // SOURCE/TARGET RESPONSE_SIZE_BYTES
            .add(
                Optional.ofNullable(tuple.sourcePair)
                    .flatMap(sp -> Optional.ofNullable(sp.responseData))
                    .map(rd -> rd.stream().mapToInt(bArr -> bArr.length).sum() + "")
                    .orElse(MISSING_STR)
                    + "/"
                    + Optional.ofNullable(tuple.targetResponseData)
                        .map(rd -> rd.stream().mapToInt(bArr -> bArr.length).sum() + "")
                        .orElse(MISSING_STR)
            )
            // SOURCE/TARGET LATENCY
            .add(
                s.map(r -> "" + r.get(ParsedHttpMessagesAsDicts.RESPONSE_TIME_MS_KEY)).orElse(MISSING_STR)
                    + "/"
                    + t.map(r -> "" + r.get(ParsedHttpMessagesAsDicts.RESPONSE_TIME_MS_KEY)).orElse(MISSING_STR)
            )
            .toString();
    }

}
