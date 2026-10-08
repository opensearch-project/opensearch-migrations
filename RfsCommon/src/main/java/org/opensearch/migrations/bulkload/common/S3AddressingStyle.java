package org.opensearch.migrations.bulkload.common;

import java.util.Locale;
import java.util.function.UnaryOperator;

import lombok.experimental.UtilityClass;

/**
 * Resolves S3 bucket addressing (path-style vs virtual-hosted) from the {@code AWS_S3_ADDRESSING_STYLE}
 * environment variable, which the migration workflow sets per snapshot repository. The AWS Java SDK
 * does not read that variable itself, so every S3 client that touches a snapshot repo consults this.
 */
@UtilityClass
public class S3AddressingStyle {
    public static final String ENV_VAR = "AWS_S3_ADDRESSING_STYLE";

    /**
     * @param defaultPathStyle what the caller would use without an explicit setting (typically
     *                         "path-style whenever a custom endpoint is set")
     * @return {@code true} for path-style, {@code false} for virtual-hosted
     */
    public static boolean forcePathStyle(boolean defaultPathStyle) {
        return forcePathStyle(defaultPathStyle, System::getenv);
    }

    static boolean forcePathStyle(boolean defaultPathStyle, UnaryOperator<String> env) {
        var value = env.apply(ENV_VAR);
        if (value == null) {
            return defaultPathStyle;
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "path":
                return true;
            case "virtual":
                return false;
            default:
                // "", "auto", or anything unrecognized keeps the caller's default.
                return defaultPathStyle;
        }
    }
}
