// see the helm chart for where this gets installed
import {AllowLiteralOrExpression, configMapKey, expr, typeToken} from "@opensearch-migrations/argo-workflow-builders";

export const emptySecretName = expr.literal("empty");

export function getSourceHttpAuthCreds(configMapNameOrEmpty: AllowLiteralOrExpression<string>) {
    const configMapName =
        expr.ternary(expr.isEmpty(configMapNameOrEmpty), emptySecretName, configMapNameOrEmpty);
    return {
        SOURCE_USERNAME: {secretKeyRef: configMapKey(configMapName, "username", true), type: typeToken<string>()},
        SOURCE_PASSWORD: {secretKeyRef: configMapKey(configMapName, "password", true), type: typeToken<string>()}
    } as const;
}

export function getTargetHttpAuthCreds(configMapNameOrEmpty: AllowLiteralOrExpression<string>) {
    const configMapName =
        expr.ternary(expr.isEmpty(configMapNameOrEmpty), emptySecretName, configMapNameOrEmpty);
    return {
        TARGET_USERNAME: {secretKeyRef: configMapKey(configMapName, "username", true), type: typeToken<string>()},
        TARGET_PASSWORD: {secretKeyRef: configMapKey(configMapName, "password", true), type: typeToken<string>()}
    } as const;
}