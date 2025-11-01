package org.opensearch.migrations.arguments;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class ArgNameConstants {

    private ArgNameConstants() {
        throw new IllegalStateException("Constant class should not be instantiated");
    }

    public static final Pattern POSSIBLE_CREDENTIALS_ARG_FLAG_NAMES =
        Pattern.compile("--(?:target|source)(?:(?:-u|U)sername|(?:-p|P)assword)");

    public static final String TARGET_PASSWORD_ARG_KEBAB_CASE = "--target-password";
    public static final String TARGET_PASSWORD_ARG_CAMEL_CASE = "--targetPassword";
    public static final String TARGET_USERNAME_ARG_KEBAB_CASE = "--target-username";
    public static final String TARGET_USERNAME_ARG_CAMEL_CASE = "--targetUsername";
    public static final String SOURCE_PASSWORD_ARG_KEBAB_CASE = "--source-password";
    public static final String SOURCE_PASSWORD_ARG_CAMEL_CASE = "--sourcePassword";
    public static final String SOURCE_USERNAME_ARG_KEBAB_CASE = "--source-username";
    public static final String SOURCE_USERNAME_ARG_CAMEL_CASE = "--sourceUsername";
    public static final List<String> CENSORED_TARGET_ARGS = List.of(TARGET_PASSWORD_ARG_KEBAB_CASE, TARGET_PASSWORD_ARG_CAMEL_CASE);
    public static final List<String> CENSORED_SOURCE_ARGS = List.of(SOURCE_PASSWORD_ARG_KEBAB_CASE, SOURCE_PASSWORD_ARG_CAMEL_CASE);

    @SafeVarargs
    public static List<String> joinLists(List<String>... lists) {
        List<String> result = new ArrayList<>();
        for (List<String> list : lists) {
            result.addAll(list);
        }
        return result;
    }
}
