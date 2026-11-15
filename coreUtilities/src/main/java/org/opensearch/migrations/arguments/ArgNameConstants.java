package org.opensearch.migrations.arguments;

import java.util.Set;
import java.util.regex.Pattern;

public class ArgNameConstants {

    private ArgNameConstants() {
        throw new IllegalStateException("Constant class should not be instantiated");
    }

    public static final Pattern POSSIBLE_CREDENTIALS_ARG_FLAG_NAMES =
        Pattern.compile("--(?:target|source|coordinator|kafka(?:Traffic)?)(?:(?:-u|U)sername|(?:-p|P)assword)");

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
    public static final String AZURE_STORAGE_ACCOUNT_KEY_ARG_KEBAB_CASE = "--azure-storage-account-key";
    public static final String AZURE_STORAGE_ACCOUNT_KEY_ARG_CAMEL_CASE = "--azureStorageAccountKey";
    public static final String AZURE_STORAGE_SAS_TOKEN_ARG_KEBAB_CASE = "--azure-storage-sas-token";
    public static final String AZURE_STORAGE_SAS_TOKEN_ARG_CAMEL_CASE = "--azureStorageSasToken";
    public static final Set<String> AZURE_STORAGE_CREDENTIAL_ARGS = Set.of(
        AZURE_STORAGE_ACCOUNT_KEY_ARG_KEBAB_CASE,
        AZURE_STORAGE_ACCOUNT_KEY_ARG_CAMEL_CASE,
        AZURE_STORAGE_SAS_TOKEN_ARG_KEBAB_CASE,
        AZURE_STORAGE_SAS_TOKEN_ARG_CAMEL_CASE
    );
    public static final Set<String> CENSORED_ARGS = Set.of(
        AZURE_STORAGE_ACCOUNT_KEY_ARG_KEBAB_CASE,
        AZURE_STORAGE_ACCOUNT_KEY_ARG_CAMEL_CASE,
        AZURE_STORAGE_SAS_TOKEN_ARG_KEBAB_CASE,
        AZURE_STORAGE_SAS_TOKEN_ARG_CAMEL_CASE,
        TARGET_PASSWORD_ARG_KEBAB_CASE,
        TARGET_PASSWORD_ARG_CAMEL_CASE,
        SOURCE_PASSWORD_ARG_KEBAB_CASE,
        SOURCE_PASSWORD_ARG_CAMEL_CASE,
        COORDINATOR_PASSWORD_ARG_KEBAB_CASE,
        COORDINATOR_PASSWORD_ARG_CAMEL_CASE,
        "--kafkaPassword",
        "--kafka-password",
        "--kafkaTrafficPassword",
        "--kafka-traffic-password"
    );
}
