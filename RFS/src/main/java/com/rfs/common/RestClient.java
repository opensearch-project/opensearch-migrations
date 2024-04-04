package com.rfs.common;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RestClient {
    private static final Logger logger = LogManager.getLogger(RestClient.class);

    public class Response {
        public final int code;
        public final String body;
        public final String message;

        public Response(int responseCode, String responseBody, String responseMessage) {
            this.code = responseCode;
            this.body = responseBody;
            this.message = responseMessage;
        }
    }

    public final ConnectionDetails connectionDetails;

    public RestClient(ConnectionDetails connectionDetails) {
        this.connectionDetails = connectionDetails;
    }

    public Response get(String path, boolean quietLogging) throws Exception {
        String urlString = connectionDetails.host + "/" + path;
        
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        // Set the request method
        conn.setRequestMethod("GET");
        
        // Set the necessary headers
        setAuthHeader(conn);

        // Enable input and output streams
        conn.setDoOutput(true);

        // Report the results
        int responseCode = conn.getResponseCode();

        String responseBody;
        if (responseCode >= HttpURLConnection.HTTP_BAD_REQUEST) {
            // Read error stream if present
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                responseBody = br.lines().collect(Collectors.joining(System.lineSeparator()));
            }
        } else {
            // Read input stream for successful requests
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                responseBody = br.lines().collect(Collectors.joining(System.lineSeparator()));
            }
        }

        if (quietLogging || (responseCode == HttpURLConnection.HTTP_CREATED || responseCode == HttpURLConnection.HTTP_OK)) {
            logger.debug("Response Code: " + responseCode + ", Response Message: " + conn.getResponseMessage() + ", Response Body: " + responseBody);
        } else {
            logger.error("Response Code: " + responseCode + ", Response Message: " + conn.getResponseMessage() + ", Response Body: " + responseBody);
        }

        conn.disconnect();

        return new Response(responseCode, responseBody, conn.getResponseMessage());
    }
    
    public Response put(String path, String body, boolean quietLogging) throws Exception {
        String urlString = connectionDetails.host + "/" + path;
        
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        // Set the request method
        conn.setRequestMethod("PUT");

        // Set the necessary headers
        conn.setRequestProperty("Content-Type", "application/json");
        setAuthHeader(conn);

        // Enable input and output streams
        conn.setDoOutput(true);

        // Write the request body
        try(OutputStream os = conn.getOutputStream()) {
            byte[] input = body.getBytes("utf-8");
            os.write(input, 0, input.length);           
        }        

        // Report the results
        int responseCode = conn.getResponseCode();

        String responseBody;
        if (responseCode >= HttpURLConnection.HTTP_BAD_REQUEST) {
            // Read error stream if present
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                responseBody = br.lines().collect(Collectors.joining(System.lineSeparator()));
            }
        } else {
            // Read input stream for successful requests
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                responseBody = br.lines().collect(Collectors.joining(System.lineSeparator()));
            }
        }

        if (quietLogging || (responseCode == HttpURLConnection.HTTP_CREATED || responseCode == HttpURLConnection.HTTP_OK)) {
            logger.debug("Response Code: " + responseCode + ", Response Message: " + conn.getResponseMessage() + ", Response Body: " + responseBody);
        } else {
            logger.error("Response Code: " + responseCode + ", Response Message: " + conn.getResponseMessage() + ", Response Body: " + responseBody);
        }

        conn.disconnect();
        
        return new Response(responseCode, responseBody, conn.getResponseMessage());
    }

    private void setAuthHeader(HttpURLConnection conn) {
        if (connectionDetails.authType == ConnectionDetails.AuthType.BASIC) {
            String auth = connectionDetails.username + ":" + connectionDetails.password;
            byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
            String authHeaderValue = "Basic " + new String(encodedAuth);
            conn.setRequestProperty("Authorization", authHeaderValue);
        }
    }
}
