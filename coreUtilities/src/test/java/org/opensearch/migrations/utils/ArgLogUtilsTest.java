package org.opensearch.migrations.utils;

import java.util.List;

import org.opensearch.migrations.arguments.ArgLogUtils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ArgLogUtilsTest {
    @Test
    void testNoPasswordArgs() {
        String[] args = {"--host", "localhost", "--port", "9200"};
        List<String> redacted = ArgLogUtils.getRedactedArgs(args,  List.of("--targetPassword", "--target-password"));

        Assertions.assertEquals(List.of("--host", "localhost", "--port", "9200"), redacted);
    }

    @Test
    void testTargetPasswordArgRedacted() {
        String[] args = {"--target-password", "secret123", "--host", "localhost"};
        List<String> redacted = ArgLogUtils.getRedactedArgs(args,  List.of("--targetPassword", "--target-password"));

        Assertions.assertEquals(List.of("--target-password", ArgLogUtils.CENSORED_VALUE, "--host", "localhost"), redacted);
    }

    @Test
    void testTargetPasswordCamelCaseRedacted() {
        String[] args = {"--targetPassword", "hunter2"};
        List<String> redacted = ArgLogUtils.getRedactedArgs(args,  List.of("--targetPassword", "--target-password"));

        Assertions.assertEquals(List.of("--targetPassword", ArgLogUtils.CENSORED_VALUE), redacted);
    }

    @Test
    void testMultiplePasswordFlags() {
        String[] args = {
                "--target-password", "firstSecret",
                "--host", "localhost",
                "--targetPassword", "secondSecret"
        };
        List<String> redacted = ArgLogUtils.getRedactedArgs(args,  List.of("--targetPassword", "--target-password"));

        Assertions.assertEquals(List.of(
                "--target-password", ArgLogUtils.CENSORED_VALUE,
                "--host", "localhost",
                "--targetPassword", ArgLogUtils.CENSORED_VALUE
        ), redacted);
    }

    @Test
    void testPasswordFlagAtEnd() {
        String[] args = {"--target-password"};
        List<String> redacted = ArgLogUtils.getRedactedArgs(args,  List.of("--targetPassword", "--target-password"));

        Assertions.assertEquals(List.of("--target-password"), redacted); // nothing to censor after
    }
}
