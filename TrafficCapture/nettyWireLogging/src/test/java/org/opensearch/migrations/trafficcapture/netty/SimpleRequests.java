package org.opensearch.migrations.trafficcapture.netty;

public class SimpleRequests {
    public static String SMALL_POST = "POST / HTTP/1.1\r\n"
        + "Host: localhost\r\n"
        + "Content-Type: application/x-www-form-urlencoded\r\n"
        + "Content-Length: 16\r\n"
        + "\r\n"
        + "FAKE_UPLOAD_DATA";

    public static String HEALTH_CHECK = "POST / HTTP/1.1\r\n"
        + "Host: localhost\r\n"
        + "User-Agent: uploader\r\n"
        + "Content-Length: 27\r\n"
        + "\r\n"
        + "field1=value1&field2=value2";
}
