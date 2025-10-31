package org.opensearch.migrations.jcommander;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class EnvVarParameterPullerTest {

    // Test parameter classes
    static class TestParams {
        @Parameter(names = {"--target-username", "--targetUsername"})
        String targetUsername;

        @Parameter(names = {"--target-password"})
        String targetPassword;

        @Parameter(names = {"--max-requests"})
        int maxRequests = 0;

        @Parameter(names = {"--speedup-factor"})
        double speedupFactor = 1.0;

        @Parameter(names = {"--enable-feature"})
        boolean enableFeature = false;

        @Parameter(names = {"--timeout"})
        long timeoutMs = 0L;

        @ParametersDelegate
        NestedParams nestedParams = new NestedParams();
    }

    static class NestedParams {
        @Parameter(names = {"--nested-value"})
        String nestedValue;

        @Parameter(names = {"--nested-count"})
        int nestedCount = 0;
    }

    /**
     * Helper method to create a simple EnvVarGetter from a map
     */
    private EnvVarParameterPuller.EnvVarGetter createMockEnvGetter(Map<String, String> envVars) {
        return envVars::get;
    }

    @Test
    void testInjectStringParameter() {
        TestParams params = new TestParams();
        Map<String, String> env = new HashMap<>();
        env.put("TARGET_USERNAME_CMD_LINE_ARG", "testuser");

        EnvVarParameterPuller.injectFromEnv(params, createMockEnvGetter(env), "", "_CMD_LINE_ARG");

        Assertions.assertEquals("testuser", params.targetUsername);
    }

    @Test
    void testInjectMultipleParameters() {
        TestParams params = new TestParams();
        Map<String, String> env = new HashMap<>();
        env.put("TARGET_USERNAME_CMD_LINE_ARG", "testuser");
        env.put("TARGET_PASSWORD_CMD_LINE_ARG", "testpass");
        env.put("MAX_REQUESTS_CMD_LINE_ARG", "100");

        EnvVarParameterPuller.injectFromEnv(params, createMockEnvGetter(env), "", "_CMD_LINE_ARG");

        Assertions.assertEquals("testuser", params.targetUsername);
        Assertions.assertEquals("testpass", params.targetPassword);
        Assertions.assertEquals(100, params.maxRequests);
    }

    @Test
    void testInjectIntParameter() {
        TestParams params = new TestParams();
        Map<String, String> env = new HashMap<>();
        env.put("MAX_REQUESTS_CMD_LINE_ARG", "500");

        EnvVarParameterPuller.injectFromEnv(params, createMockEnvGetter(env), "", "_CMD_LINE_ARG");

        Assertions.assertEquals(500, params.maxRequests);
    }

    @Test
    void testInjectDoubleParameter() {
        TestParams params = new TestParams();
        Map<String, String> env = new HashMap<>();
        env.put("SPEEDUP_FACTOR_CMD_LINE_ARG", "2.5");

        EnvVarParameterPuller.injectFromEnv(params, createMockEnvGetter(env), "", "_CMD_LINE_ARG");

        Assertions.assertEquals(2.5, params.speedupFactor, 0.001);
    }

    @Test
    void testInjectBooleanParameter() {
        TestParams params = new TestParams();
        Map<String, String> env = new HashMap<>();
        env.put("ENABLE_FEATURE_CMD_LINE_ARG", "true");

        EnvVarParameterPuller.injectFromEnv(params, createMockEnvGetter(env), "", "_CMD_LINE_ARG");

        Assertions.assertTrue(params.enableFeature);
    }

    @Test
    void testInjectLongParameter() {
        TestParams params = new TestParams();
        Map<String, String> env = new HashMap<>();
        env.put("TIMEOUT_CMD_LINE_ARG", "5000");

        EnvVarParameterPuller.injectFromEnv(params, createMockEnvGetter(env), "", "_CMD_LINE_ARG");

        Assertions.assertEquals(5000L, params.timeoutMs);
    }

    @Test
    void testCamelCaseToSnakeCaseConversion() {
        TestParams params = new TestParams();
        Map<String, String> env = new HashMap<>();
        env.put("TARGET_USERNAME_CMD_LINE_ARG", "user1");
        env.put("TARGET_PASSWORD_CMD_LINE_ARG", "pass1");
        env.put("SPEEDUP_FACTOR_CMD_LINE_ARG", "1.5");

        EnvVarParameterPuller.injectFromEnv(params, createMockEnvGetter(env), "", "_CMD_LINE_ARG");

        Assertions.assertEquals("user1", params.targetUsername);
        Assertions.assertEquals("pass1", params.targetPassword);
        Assertions.assertEquals(1.5, params.speedupFactor, 0.001);
    }

    @Test
    void testKebabCaseParameterNameConversion() {
        TestParams params = new TestParams();
        Map<String, String> env = new HashMap<>();
        env.put("MAX_REQUESTS_CMD_LINE_ARG", "200");

        EnvVarParameterPuller.injectFromEnv(params, createMockEnvGetter(env), "", "_CMD_LINE_ARG");

        Assertions.assertEquals(200, params.maxRequests);
    }

    @Test
    void testNestedParametersDelegateInjection() {
        TestParams params = new TestParams();
        Map<String, String> env = new HashMap<>();
        env.put("NESTED_VALUE_CMD_LINE_ARG", "nested_test");
        env.put("NESTED_COUNT_CMD_LINE_ARG", "42");

        EnvVarParameterPuller.injectFromEnv(params, createMockEnvGetter(env), "", "_CMD_LINE_ARG");

        Assertions.assertNotNull(params.nestedParams);
        Assertions.assertEquals("nested_test", params.nestedParams.nestedValue);
        Assertions.assertEquals(42, params.nestedParams.nestedCount);
    }

    @Test
    void testMixedParametersAndNestedParameters() {
        TestParams params = new TestParams();
        Map<String, String> env = new HashMap<>();
        env.put("TARGET_USERNAME_CMD_LINE_ARG", "mainuser");
        env.put("MAX_REQUESTS_CMD_LINE_ARG", "150");
        env.put("NESTED_VALUE_CMD_LINE_ARG", "nested");
        env.put("NESTED_COUNT_CMD_LINE_ARG", "10");

        EnvVarParameterPuller.injectFromEnv(params, createMockEnvGetter(env), "", "_CMD_LINE_ARG");

        Assertions.assertEquals("mainuser", params.targetUsername);
        Assertions.assertEquals(150, params.maxRequests);
        Assertions.assertEquals("nested", params.nestedParams.nestedValue);
        Assertions.assertEquals(10, params.nestedParams.nestedCount);
    }

    @Test
    void testNoEnvironmentVariablesSet() {
        TestParams params = new TestParams();
        Map<String, String> env = new HashMap<>();

        EnvVarParameterPuller.injectFromEnv(params, createMockEnvGetter(env), "", "_CMD_LINE_ARG");

        Assertions.assertNull(params.targetUsername);
        Assertions.assertNull(params.targetPassword);
        Assertions.assertEquals(0, params.maxRequests);
        Assertions.assertEquals(1.0, params.speedupFactor, 0.001);
        Assertions.assertFalse(params.enableFeature);
    }

    @Test
    void testInvalidIntegerValue() {
        TestParams params = new TestParams();
        Map<String, String> env = new HashMap<>();
        env.put("MAX_REQUESTS_CMD_LINE_ARG", "not_a_number");

        // Should not throw exception, just log error
        Assertions.assertDoesNotThrow(() -> EnvVarParameterPuller.injectFromEnv(params, createMockEnvGetter(env), "", ""));

        // Value should remain at default
        Assertions.assertEquals(0, params.maxRequests);
    }

    @Test
    void testInvalidDoubleValue() {
        TestParams params = new TestParams();
        Map<String, String> env = new HashMap<>();
        env.put("SPEEDUP_FACTOR_CMD_LINE_ARG", "invalid");

        Assertions.assertDoesNotThrow(() -> EnvVarParameterPuller.injectFromEnv(params, createMockEnvGetter(env), "", ""));

        Assertions.assertEquals(1.0, params.speedupFactor, 0.001);
    }

    @Test
    void testBooleanParsing() {
        TestParams params1 = new TestParams();
        Map<String, String> env1 = new HashMap<>();
        env1.put("ENABLE_FEATURE_CMD_LINE_ARG", "true");
        EnvVarParameterPuller.injectFromEnv(params1, createMockEnvGetter(env1), "", "_CMD_LINE_ARG");
        Assertions.assertTrue(params1.enableFeature);

        TestParams params2 = new TestParams();
        Map<String, String> env2 = new HashMap<>();
        env2.put("ENABLE_FEATURE_CMD_LINE_ARG", "false");
        EnvVarParameterPuller.injectFromEnv(params2, createMockEnvGetter(env2), "", "_CMD_LINE_ARG");
        Assertions.assertFalse(params2.enableFeature);

        TestParams params3 = new TestParams();
        Map<String, String> env3 = new HashMap<>();
        env3.put("ENABLE_FEATURE_CMD_LINE_ARG", "yes");
        EnvVarParameterPuller.injectFromEnv(params3, createMockEnvGetter(env3), "", "_CMD_LINE_ARG");
        Assertions.assertFalse(params3.enableFeature); // Boolean.parseBoolean only accepts "true"
    }

    @Test
    void testEmptyStringEnvironmentVariable() {
        TestParams params = new TestParams();
        Map<String, String> env = new HashMap<>();
        env.put("TARGET_USERNAME_CMD_LINE_ARG", "");

        EnvVarParameterPuller.injectFromEnv(params, createMockEnvGetter(env), "", "_CMD_LINE_ARG");

        Assertions.assertEquals("", params.targetUsername);
    }

    @Test
    void testMultipleNamingVariationsForSameField() {
        // Tests that the injector can find env vars from different parameter name formats
        TestParams params = new TestParams();
        Map<String, String> env = new HashMap<>();

        // The field targetUsername has both camelCase and kebab-case parameter names
        // Both should map to TARGET_USERNAME_CMD_LINE_ARG env var
        env.put("TARGET_USERNAME_CMD_LINE_ARG", "testuser");

        EnvVarParameterPuller.injectFromEnv(params, createMockEnvGetter(env), "", "_CMD_LINE_ARG");

        Assertions.assertEquals("testuser", params.targetUsername);
    }

    @Test
    void testToEnvVarNameConversion() {
        // Test the public conversion method
        Assertions.assertEquals(List.of("FOO_TARGET_USERNAME_CMD_LINE_ARG", "TARGET_USERNAME"), EnvVarParameterPuller.toEnvVarNames("--targetUsername", "FOO_", "_CMD_LINE_ARG"));
        Assertions.assertEquals(List.of("SOURCE_PASSWORD_CMD_LINE_ARG", "SOURCE_PASSWORD"), EnvVarParameterPuller.toEnvVarNames("--source-password", "", "_CMD_LINE_ARG"));
        Assertions.assertEquals(List.of("KAFKA_TRAFFIC_BROKERS_CMD_LINE_ARG"), EnvVarParameterPuller.toEnvVarNames("--kafkaTrafficBrokers", "", "_CMD_LINE_ARG"));
        Assertions.assertEquals(List.of("MAX_CONCURRENT_REQUESTS_CMD_LINE_ARG"), EnvVarParameterPuller.toEnvVarNames("--maxConcurrentRequests", "", "_CMD_LINE_ARG"));
        Assertions.assertEquals(List.of("SPEEDUP_FACTOR_CMD_LINE_ARG"), EnvVarParameterPuller.toEnvVarNames("--speedup-factor", "", "_CMD_LINE_ARG"));
        Assertions.assertEquals(List.of("SIMPLE_CMD_LINE_ARG"), EnvVarParameterPuller.toEnvVarNames("--simple", "", "_CMD_LINE_ARG"));
        Assertions.assertEquals(List.of("A_B_C_CMD_LINE_ARG"), EnvVarParameterPuller.toEnvVarNames("--aBC", "", "_CMD_LINE_ARG"));
    }

    @Test
    void testDefaultEnvGetterUsesSystemEnv() {
        // This test actually uses real system environment variables
        // It will only pass if the test environment has these variables set
        // You could skip this test in CI if needed

        TestParams params = new TestParams();

        // Try to inject using the default System.getenv() method
        EnvVarParameterPuller.injectFromEnv(params, "", "_CMD_LINE_ARG");

        // We can't Assertions.assert specific values since we don't control the environment,
        // but we can verify it doesn't throw an exception
        Assertions.assertNotNull(params);
    }

    @Test
    void testLambdaEnvGetter() {
        TestParams params = new TestParams();

        // Use a custom lambda that returns predictable values
        EnvVarParameterPuller.EnvVarGetter customGetter = name -> {
            if ("TARGET_USERNAME_CMD_LINE_ARG".equals(name)) {
                return "lambda_user";
            }
            if ("MAX_REQUESTS_CMD_LINE_ARG".equals(name)) {
                return "777";
            }
            return null;
        };

        EnvVarParameterPuller.injectFromEnv(params, customGetter, "", "_CMD_LINE_ARG");

        Assertions.assertEquals("lambda_user", params.targetUsername);
        Assertions.assertEquals(777, params.maxRequests);
    }
}
