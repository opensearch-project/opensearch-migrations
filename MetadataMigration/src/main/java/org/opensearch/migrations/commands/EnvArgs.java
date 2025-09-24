package org.opensearch.migrations.commands;

import java.util.ArrayList;
import java.util.List;

import org.opensearch.migrations.MigrateOrEvaluateArgs;
import org.opensearch.migrations.arguments.ArgNameConstants;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EnvArgs {

    private EnvArgs() {
        throw new IllegalStateException("EnvArgs utility class should not instantiated");
    }

    public static void injectFromEnv(MigrateOrEvaluateArgs args) {
        List<String> addedEnvParams = new ArrayList<>();
        if (args.sourceArgs.username == null && System.getenv(ArgNameConstants.SOURCE_USERNAME_ENV_ARG) != null) {
            args.sourceArgs.username = System.getenv(ArgNameConstants.SOURCE_USERNAME_ENV_ARG);
            addedEnvParams.add(ArgNameConstants.SOURCE_USERNAME_ENV_ARG);
        }
        if (args.sourceArgs.password == null && System.getenv(ArgNameConstants.SOURCE_PASSWORD_ENV_ARG) != null) {
            args.sourceArgs.password = System.getenv(ArgNameConstants.SOURCE_PASSWORD_ENV_ARG);
            addedEnvParams.add(ArgNameConstants.SOURCE_PASSWORD_ENV_ARG);
        }
        if (args.targetArgs.username == null && System.getenv(ArgNameConstants.TARGET_USERNAME_ENV_ARG) != null) {
            args.targetArgs.username = System.getenv(ArgNameConstants.TARGET_USERNAME_ENV_ARG);
            addedEnvParams.add(ArgNameConstants.TARGET_USERNAME_ENV_ARG);
        }
        if (args.targetArgs.password == null && System.getenv(ArgNameConstants.TARGET_PASSWORD_ENV_ARG) != null) {
            args.targetArgs.password = System.getenv(ArgNameConstants.TARGET_PASSWORD_ENV_ARG);
            addedEnvParams.add(ArgNameConstants.TARGET_PASSWORD_ENV_ARG);
        }
        if (!addedEnvParams.isEmpty()) {
            log.info("Adding parameters from the following expected environment variables: {}", addedEnvParams);
        }
    }
}
