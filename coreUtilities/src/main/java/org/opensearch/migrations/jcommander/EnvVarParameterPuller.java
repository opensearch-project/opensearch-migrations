package org.opensearch.migrations.jcommander;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class to inject environment variables into JCommander parameter objects.
 * Converts parameter names to UPPER_SNAKE_CASE for environment variable lookup.
 */
@Slf4j
public class EnvVarParameterPuller {

    public static final String DEFAULT_SUFFIX = "_CMD_LINE_ARG";
    private static final Pattern CAMEL_CASE_PATTERN = Pattern.compile("([A-Z])");

    /**
     * Interface for retrieving environment variables.
     * Allows for dependency injection and testing.
     */
    @FunctionalInterface
    public interface EnvVarGetter {
        String getEnv(String name);
    }

    private EnvVarParameterPuller() {
        throw new IllegalStateException("EnvParameterInjector utility class should not be instantiated");
    }

    /**
     * Injects environment variables into the provided parameters object using System.getenv().
     * Only sets fields that are currently null (doesn't override command-line args).
     *
     * @param params The parameters object to inject environment variables into
     * @param prefix
     * @param suffix
     */
    public static<T> T injectFromEnv(T params, String prefix, String suffix) {
        return injectFromEnv(params, System::getenv, prefix, suffix);
    }

    public static<T> T injectFromEnv(T params, String prefix) {
        return injectFromEnv(params, System::getenv, prefix, DEFAULT_SUFFIX);
    }

    public static<T> T injectFromEnv(T params, EnvVarGetter envVarGetter, String prefix) {
        return injectFromEnv(params, envVarGetter, prefix, DEFAULT_SUFFIX);
    }

    /**
     * Injects environment variables into the provided parameters object using a custom getter.
     * Only sets fields that are currently null (doesn't override command-line args).
     *
     * @param params       The parameters object to inject environment variables into
     * @param envVarGetter The function to retrieve environment variable values
     * @param prefix
     * @param suffix
     */
    public static<T> T injectFromEnv(T params, EnvVarGetter envVarGetter, String prefix, String suffix) {
        List<String> addedEnvParams = new ArrayList<>();
        injectFromEnvRecursive(params, envVarGetter, addedEnvParams, prefix, suffix);

        if (!addedEnvParams.isEmpty()) {
            log.info("Adding parameters from the following environment variables: {}", addedEnvParams);
        }
        return params;
    }

    /**
     * Recursively processes parameter objects, including @ParametersDelegate fields.
     */
    private static void injectFromEnvRecursive(Object params,
                                               EnvVarGetter envVarGetter,
                                               List<String> addedEnvParams,
                                               String prefix,
                                               String suffix)
    {
        if (params == null) {
            return;
        }

        Class<?> clazz = params.getClass();

        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);

            try {
                if (field.isAnnotationPresent(ParametersDelegate.class)) {
                    var delegatedObject = field.get(params);
                    if (delegatedObject != null) {
                        injectFromEnvRecursive(delegatedObject, envVarGetter, addedEnvParams, prefix, suffix);
                    }
                } else if (field.isAnnotationPresent(Parameter.class)) {
                    var annotation = field.getAnnotation(Parameter.class);
                    processParameterField(params, field, annotation, envVarGetter, addedEnvParams, prefix, suffix);
                }
            } catch (IllegalAccessException e) {
                log.warn("Could not access field: {}", field.getName(), e);
            }
        }
    }

    private static void processParameterField(Object params,
                                              Field field,
                                              Parameter annotation,
                                              EnvVarGetter envVarGetter,
                                              List<String> addedEnvParams,
                                              String prefix,
                                              String suffix)
        throws IllegalAccessException
    {
        // Try to find environment variable value
        String envValue = findEnvValue(field.getName(), annotation, envVarGetter, prefix, suffix);
        if (envValue != null) {
            setFieldValue(params, field, envValue);
            String envVarName = toEnvVarName(field.getName(), prefix, suffix);
            addedEnvParams.add(envVarName);
        }
    }

    private static String findEnvValue(String fieldName, Parameter annotation, EnvVarGetter envVarGetter,
                                       String prefix, String suffix) {
        // Also try parameter names from annotation (converted to env var format)
        if (annotation.names().length > 0) {
            for (String name : annotation.names()) {
                // Remove leading dashes and convert
                var envName = toEnvVarName(name, prefix, suffix);
                var envValue = envVarGetter.getEnv(envName);
                if (envValue != null) {
                    return envValue;
                }
            }
        }

        return null;
    }

    /**
     * Converts a field name to UPPER_SNAKE_CASE + "_ENV_ARG" environment variable format.
     * Examples:
     *   targetUsername -> TARGET_USERNAME_ENV_ARG
     */
    public static String toEnvVarName(String fieldName, String prefix, String suffix) {
        fieldName = fieldName.replaceAll("^-+", "");
        String normalized = fieldName.replace("-", "_");

        Matcher matcher = CAMEL_CASE_PATTERN.matcher(normalized);
        String snakeCase = matcher.replaceAll("_$1");
        return prefix + snakeCase.toUpperCase() + suffix;
    }

    private static void setFieldValue(Object params, Field field, String value) throws IllegalAccessException {
        Class<?> type = field.getType();

        try {
            if (type == String.class) {
                field.set(params, value);
            } else if (type == int.class || type == Integer.class) {
                field.set(params, Integer.parseInt(value));
            } else if (type == double.class || type == Double.class) {
                field.set(params, Double.parseDouble(value));
            } else if (type == boolean.class || type == Boolean.class) {
                field.set(params, Boolean.parseBoolean(value));
            } else if (type == long.class || type == Long.class) {
                field.set(params, Long.parseLong(value));
            } else if (type == float.class || type == Float.class) {
                field.set(params, Float.parseFloat(value));
            } else {
                log.warn("Unsupported field type for environment variable injection: {} (field: {})",
                    type.getName(), field.getName());
            }
        } catch (NumberFormatException e) {
            log.error("Failed to parse environment variable value '{}' for field '{}' of type {}",
                value, field.getName(), type.getName(), e);
        }
    }
}