package org.opensearch.migrations.trafficcapture.netty;

public class SimpleRequests {
    public static String SMALL_POST = "POST / HTTP/1.1\n" +
            "Host: localhost\n" +
            "Content-Type: application/x-www-form-urlencoded\n" +
            "Content-Length: 27\n" +
            "\n" +
            "field1=value1&field2=value2";
}
