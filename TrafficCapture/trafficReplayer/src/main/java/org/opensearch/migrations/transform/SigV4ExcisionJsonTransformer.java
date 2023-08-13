package org.opensearch.migrations.transform;

import com.google.gson.Gson;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import org.opensearch.migrations.replay.SigV4Signer;
import org.opensearch.migrations.replay.datahandlers.PayloadAccessFaultingMap;
import org.opensearch.migrations.replay.datahandlers.http.HttpJsonMessageWithFaultingPayload;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.net.URI;
import java.util.regex.Pattern;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

/**
 * This is a JsonTransformer that is meant to remove header fields related to a sigV4 signature.
 */
@Slf4j
public class SigV4ExcisionJsonTransformer implements JsonTransformer {

    public static final String AUTHORIZATION_KEYNAME = "Authorization";
    public static final String SECURITY_TOKEN_KEYNAME = "X-Amz-Security-Token";
    public SigV4Signer signer;

    @Override
    public Object transformJson(Object incomingJson) {

        if (!(incomingJson instanceof Map)) {
            log.info("I got to line 37"); return incomingJson;
        } else {
            log.info("I got to line 39"); return transformHttpMessage((Map) incomingJson);
        }
    }

    @SneakyThrows
    private Object transformHttpMessage(Map<String, Object> httpMsg) {
        Map old = (Map) httpMsg.get(HttpJsonMessageWithFaultingPayload.HEADERS);
        Map<String, Object> headers = (Map<String, Object>) httpMsg.get("headers");

        log.info("I got to line 46");
        log.info("OK92 - Entries in httpMsg:");
        for (Map.Entry<String, Object> entry : httpMsg.entrySet()) {
            log.info(entry.getKey() + ": " + entry.getValue());
        }

        log.info("OK92 - line 52");
        HttpJsonMessageWithFaultingPayload msg = new HttpJsonMessageWithFaultingPayload();
        msg.setMethod((String) httpMsg.get("method"));
        msg.setUri((String) httpMsg.get("URI"));
        msg.setProtocol("https");

        log.info("OK92 - line 58");

        System.setProperty("aws.accessKeyId", "accesskeyid");
        System.setProperty("aws.secretAccessKey", "secretaccesskey");
        System.setProperty("aws.sessionToken", "sessiontoken");

        log.info("OK92 - line 64");
        Object hostname = headers.get("Host");
        URI baseEndpoint = new URI("https://" + ((List<String>) hostname).get(0));
        log.info("OK92 - line 65");

        URI endpoint = baseEndpoint.resolve(msg.uri());
        log.info("OK92 - line 68");

        signer = new SigV4Signer(msg, DefaultCredentialsProvider.create(), endpoint);
        log.info("OK92 - line 71");

        //ByteBuf payloadChunk1 = Unpooled.copiedBuffer((String) httpMsg.get("payload"), StandardCharsets.UTF_8);


        //log.info("Payload object type: " + payloadObj.getClass().getName());

        Object payloadObject = httpMsg.get("payload");
        if (payloadObject instanceof PayloadAccessFaultingMap) {
            PayloadAccessFaultingMap payloadMap = (PayloadAccessFaultingMap) payloadObject;
            log.info("OK92 - LINE 85 - DID I GET HERE ?!?!?!");
            Object actualPayload = payloadMap.get(PayloadAccessFaultingMap.INLINED_JSON_BODY_DOCUMENT_KEY);

            Gson gson = new Gson();
            String jsonString = gson.toJson(actualPayload);
            log.info(jsonString);
            ByteBuf payloadChunk1 = Unpooled.copiedBuffer(jsonString, StandardCharsets.UTF_8);
            payloadChunk1.readerIndex(0);

            if (jsonString != "") {
                log.info("OK92 - LINE 90 - DID I GET HERE ?!?!?!");
                signer.processNextPayload(payloadChunk1);
            }
            else {
                log.info("OK92 - LINE 92 - DID I GET HERE ?!?!?!");
               // signer.processNextPayload((ByteBuf) httpMsg.get("payload"));
            }

            //log.info("OK92 - Line 87 - actualPayload:");
            //log.info(actualPayload.toString());

        }

        //signer.processNextPayload((ByteBuf) httpMsg.get("payload"));
        log.info("OK92 - line 78");
        Map<String, List<String>> signatureHeaders = signer.getSignatureheaders("es", "us-east-1");
        log.info("OK92 - line 80");
        // REMOVE ME LATER

        List<Map.Entry<String, String>> headerEntries = signatureHeaders.entrySet().stream()
                .map(kvp -> Map.entry(kvp.getKey(), kvp.getValue().get(0)))
                .collect(Collectors.toList());

        log.info("OK92 : line 87");
        headerEntries.forEach(header -> log.info(header.getKey() + ": " + header.getValue()));

        // REMOVE ME LATER

        Map<String, List<String>> existingHeaders = (Map<String, List<String>>) httpMsg.get("headers");

        // headers.remove(AUTHORIZATION_KEYNAME);
        //headers.remove(SECURITY_TOKEN_KEYNAME);

        for (Map.Entry<String, List<String>> entry : signatureHeaders.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            // Assuming that the headers map stores a single string for each key.
            // If the signedHeaders has multiple values, concatenate them with a comma
            headers.put(key, String.join(", ", values));
        }

        log.info("OK92 - line 85");
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            log.info(entry.getKey() + ": " + entry.getValue());
        }

        log.info("OK92 - line 90");
        for (Map.Entry<String, List<String>> entry : existingHeaders.entrySet()) {
            log.info(entry.getKey() + ": " + entry.getValue());
        }


        //var finalHeaders = (Map) httpMsg.get(HttpJsonMessageWithFaultingPayload.HEADERS);
        // print all members in finalHeaders
        log.info("OK92 - line 102");
        //finalHeaders.forEach((k, v) -> log.info(k + ": " + v));


        //headers.remove(AUTHORIZATION_KEYNAME);
        // headers.remove(SECURITY_TOKEN_KEYNAME);
        //return httpMsg;


        return httpMsg;
    }
}