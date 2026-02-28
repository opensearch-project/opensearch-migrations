package org.opensearch.migrations.arguments;

import java.util.Set;
import java.util.regex.Pattern;

public class ArgNameConstants {

    private ArgNameConstants() {
        throw new IllegalStateException("Constant class should not be instantiated");
    }

    public static final Pattern POSSIBLE_CREDENTIALS_ARG_FLAG_NAMES =
        Pattern.compile("--(?:target|source|coordinator)(?:(?:-u|U)sername|(?:-p|P)assword)");

    public static final String TARGET_PASSWORD_ARG_KEBAB_CASE = "--target-password";
    public static final String TARGET_PASSWORD_ARG_CAMEL_CASE = "--targetPassword";
    public static final String TARGET_USERNAME_ARG_KEBAB_CASE = "--target-username";
    public static final String TARGET_USERNAME_ARG_CAMEL_CASE = "--targetUsername";
    public static final String SOURCE_PASSWORD_ARG_KEBAB_CASE = "--source-password";
    public static final String SOURCE_PASSWORD_ARG_CAMEL_CASE = "--sourcePassword";
    public static final String SOURCE_USERNAME_ARG_KEBAB_CASE = "--source-username";
    public static final String SOURCE_USERNAME_ARG_CAMEL_CASE = "--sourceUsername";
    public static final String COORDINATOR_PASSWORD_ARG_KEBAB_CASE = "--coordinator-password";
    public static final String COORDINATOR_PASSWORD_ARG_CAMEL_CASE = "--coordinatorPassword";
    public static final String COORDINATOR_USERNAME_ARG_KEBAB_CASE = "--coordinator-username";
    public static final String COORDINATOR_USERNAME_ARG_CAMEL_CASE = "--coordinatorUsername";
    public static final Set<String> CENSORED_ARGS = Set.of(
        TARGET_PASSWORD_ARG_KEBAB_CASE,
        TARGET_PASSWORD_ARG_CAMEL_CASE,
        SOURCE_PASSWORD_ARG_KEBAB_CASE,
        SOURCE_PASSWORD_ARG_CAMEL_CASE,
        COORDINATOR_PASSWORD_ARG_KEBAB_CASE,
        COORDINATOR_PASSWORD_ARG_CAMEL_CASE
    );
}
