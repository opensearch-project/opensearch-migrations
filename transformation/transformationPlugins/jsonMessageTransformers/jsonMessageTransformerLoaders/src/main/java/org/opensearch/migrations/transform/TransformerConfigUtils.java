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
            System.err.println("Specify only one of " +
                    "--" + params.getTransformerConfigParameterArgPrefix() + "transformer-config-base64" + ", " +
                    "--" + params.getTransformerConfigParameterArgPrefix() + "transformer-config" + ", or " +
                    "--" + params.getTransformerConfigParameterArgPrefix() + "transformer-config-file" + ".");
            System.exit(4);
        }

        if (params.getTransformerConfigFile() != null && !params.getTransformerConfigFile().isBlank()) {
            try {
                return Files.readString(Paths.get(params.getTransformerConfigFile()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("Error reading transformer configuration file: " + e.getMessage());
                System.exit(5);
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

}
