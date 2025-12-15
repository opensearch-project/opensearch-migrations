package org.opensearch.migrations.testutils;

import org.testcontainers.utility.DockerImageName;

public interface SharedDockerImageNames {
    DockerImageName KAFKA = DockerImageName.parse("confluentinc/cp-kafka:7.5.0");
    DockerImageName HTTPD = DockerImageName.parse("httpd:2.4.66-alpine");
    String HTTPD_EXPECTED_RESPONSE = "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01//EN\" \"http://www.w3.org/TR/html4/strict.dtd\">\n<html>\n<head>\n<title>It works! Apache httpd</title>\n</head>\n<body>\n<p>It works!</p>\n</body>\n</html>\n";
}
