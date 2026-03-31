// see the helm chart for where this gets installed
import {AllowLiteralOrExpression, configMapKey, expr, makeStringTypeProxy, typeToken} from "@opensearch-migrations/argo-workflow-builders";

export const emptySecretName = expr.literal("empty");

function resolveSecretName(configMapNameOrEmpty: AllowLiteralOrExpression<string>) {
    return expr.ternary(expr.isEmpty(configMapNameOrEmpty), emptySecretName, configMapNameOrEmpty);
}

export function getSourceHttpAuthCreds(configMapNameOrEmpty: AllowLiteralOrExpression<string>) {
    const configMapName = resolveSecretName(configMapNameOrEmpty);
    return {
        SOURCE_USERNAME: {secretKeyRef: configMapKey(configMapName, "username", true), type: typeToken<string>()},
        SOURCE_PASSWORD: {secretKeyRef: configMapKey(configMapName, "password", true), type: typeToken<string>()}
    } as const;
}

export function getTargetHttpAuthCreds(configMapNameOrEmpty: AllowLiteralOrExpression<string>) {
    const configMapName = resolveSecretName(configMapNameOrEmpty);
    return {
        TARGET_USERNAME: {secretKeyRef: configMapKey(configMapName, "username", true), type: typeToken<string>()},
        TARGET_PASSWORD: {secretKeyRef: configMapKey(configMapName, "password", true), type: typeToken<string>()}
    } as const;
}

/**
 * Returns basic auth env vars in K8s container env array format, for use in raw Deployment manifests.
 * The record-form helpers above (getTargetHttpAuthCreds, getSourceHttpAuthCreds) serve the same
 * purpose for ContainerBuilder's addEnvVarsFromRecord(), where the argoResourceRenderer converts
 * the record to K8s list format.
 */
function makeK8sSecretEnvVar(envName: string, secretName: AllowLiteralOrExpression<string>, key: string) {
    return {
        name: envName,
        valueFrom: {
            secretKeyRef: {
                name: makeStringTypeProxy(resolveSecretName(secretName)),
                key,
                optional: true
            }
        }
    };
}

export function getTargetHttpAuthCredsEnvVars(configMapNameOrEmpty: AllowLiteralOrExpression<string>) {
    return [
        makeK8sSecretEnvVar("TARGET_USERNAME", configMapNameOrEmpty, "username"),
        makeK8sSecretEnvVar("TARGET_PASSWORD", configMapNameOrEmpty, "password"),
    ];
}

export function getCoordinatorHttpAuthCredsEnvVars(configMapNameOrEmpty: AllowLiteralOrExpression<string>) {
    return [
        makeK8sSecretEnvVar("COORDINATOR_USERNAME", configMapNameOrEmpty, "username"),
        makeK8sSecretEnvVar("COORDINATOR_PASSWORD", configMapNameOrEmpty, "password"),
    ];
}