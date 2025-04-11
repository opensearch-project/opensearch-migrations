package org.opensearch.migrations.transform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;

public final class TransformerConfigUtils {
    private TransformerConfigUtils() {}

    private static int isConfigured(String s) {
        return (s == null || s.isBlank()) ? 0 : 1;
    }

    public static String getTransformerConfig(TransformerParams params) {
        var configuredCount = isConfigured(params.getTransformerConfigFile()) +
                isConfigured(params.getTransformerConfigEncoded()) +
                isConfigured(params.getTransformerConfig());
        if (configuredCount > 1) {
            throw new TooManyTransformationConfigSourcesException("Specify only one of " +
                    "--" + params.getTransformerConfigParameterArgPrefix() + "-transformer-config-base64" + ", " +
                    "--" + params.getTransformerConfigParameterArgPrefix() + "-transformer-config" + ", or " +
                    "--" + params.getTransformerConfigParameterArgPrefix() + "-transformer-config-file" +
                    ". Both Kebab case and lower Camel case are supported.");
        }

        if (params.getTransformerConfigFile() != null && !params.getTransformerConfigFile().isBlank()) {
            var configFile = Paths.get(params.getTransformerConfigFile());
            try {
                return Files.readString(configFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UnableToReadTransformationConfigException(configFile.toString(), e);
            }
        }

        if (params.getTransformerConfig() != null && !params.getTransformerConfig().isBlank()) {
            return params.getTransformerConfig();
        }

        if (params.getTransformerConfigEncoded() != null && !params.getTransformerConfigEncoded().isBlank()) {
            return new String(Base64.getDecoder().decode(params.getTransformerConfigEncoded()));
        }

        return null;
    }

    public static class TooManyTransformationConfigSourcesException extends RuntimeException {
        public TooManyTransformationConfigSourcesException(final String msg) {
            super(msg);
        }
    }

    public static class UnableToReadTransformationConfigException extends RuntimeException {
        public UnableToReadTransformationConfigException(final String filePath, final Throwable cause) {
            super("Unable to read transformation config: " + filePath, cause);
        }
    }
}
