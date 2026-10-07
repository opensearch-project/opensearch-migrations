// S3 client env vars and volumes for pods that read or write an s3:// snapshot repository.
//
// Credentials come from the repo's `s3CredentialsSecretName` Secret (keys accessKey/secretKey), mounted
// as files at S3_REPO_CREDENTIALS_MOUNT_PATH with S3_REPO_CREDENTIALS_DIR pointing there; only the Java
// S3 clients read them (see awsUtilities' S3RepoCredentials). Two reasons for files over env vars:
// AWS_ACCESS_KEY_ID would be pod-wide and win over Pod Identity/IRSA in the default provider chain, so
// SigV4-signed cluster requests (source, target, coordinator) would be signed with the S3 keys; and
// kubelet refreshes a mounted Secret in place, which the S3 clients re-read, so rotating the keys in the
// Secret needs no pod restart. The volume is optional, so with no Secret it mounts empty and the clients
// use the pod's own AWS identity.
//
// Client settings come from the per-repo ConfigMap the config processor creates at submit time (see
// generateS3RepoSettingsConfigMaps in config-processor). Every reference is optional, so an unset
// ConfigMap or key leaves the env var absent -- the SDKs then fall back to their default endpoint. This
// matters because AWS_ENDPOINT_URL_S3="" is read by the AWS SDKs as an (invalid) empty endpoint rather
// than as "unset".
import {
    AllowLiteralOrExpression,
    configMapKey,
    expr,
    makeDirectTypeProxy,
    makeStringTypeProxy,
    typeToken
} from "@opensearch-migrations/argo-workflow-builders";
import {emptySecretName} from "./basicCredsGetters";

export const S3_REPO_SETTINGS_KEYS = ["AWS_ENDPOINT_URL_S3", "AWS_S3_ADDRESSING_STYLE"] as const;
export const S3_REPO_CREDENTIALS_VOLUME_NAME = "s3-repo-credentials";
export const S3_REPO_CREDENTIALS_MOUNT_PATH = "/config/s3-repo-credentials";
const S3_REPO_CREDENTIALS_DIR_ENV_VAR = "S3_REPO_CREDENTIALS_DIR";

function resolveName(nameOrEmpty: AllowLiteralOrExpression<string>) {
    return expr.ternary(expr.isEmpty(nameOrEmpty), emptySecretName, nameOrEmpty);
}

export function getS3RepoEnvVars(settingsConfigMapNameOrEmpty: AllowLiteralOrExpression<string>) {
    const configMapName = resolveName(settingsConfigMapNameOrEmpty);
    const setting = (key: typeof S3_REPO_SETTINGS_KEYS[number]) =>
        ({configMapKeyRef: configMapKey(configMapName, key, true), type: typeToken<string>()});
    return {
        [S3_REPO_CREDENTIALS_DIR_ENV_VAR]: expr.literal(S3_REPO_CREDENTIALS_MOUNT_PATH),
        AWS_ENDPOINT_URL_S3: setting("AWS_ENDPOINT_URL_S3"),
        AWS_S3_ADDRESSING_STYLE: setting("AWS_S3_ADDRESSING_STYLE"),
    } as const;
}

/** Same env vars as {@link getS3RepoEnvVars}, in K8s container env list form for raw manifests. */
export function getS3RepoEnvVarsK8s(settingsConfigMapNameOrEmpty: AllowLiteralOrExpression<string>) {
    const configMapName = makeStringTypeProxy(resolveName(settingsConfigMapNameOrEmpty));
    return [
        {name: S3_REPO_CREDENTIALS_DIR_ENV_VAR, value: S3_REPO_CREDENTIALS_MOUNT_PATH},
        ...S3_REPO_SETTINGS_KEYS.map(key =>
            ({name: key, valueFrom: {configMapKeyRef: {name: configMapName, key, optional: true}}})),
    ];
}

/** Optional Secret volume source for the repo credentials; an unset name mounts an empty directory. */
export function getS3RepoCredentialsVolumeSource(credentialsSecretNameOrEmpty: AllowLiteralOrExpression<string>) {
    return {secret: {secretName: resolveName(credentialsSecretNameOrEmpty), optional: true}} as const;
}

/** Volume and mount for raw manifests. No subPath: kubelet does not refresh subPath mounts. */
export function getS3RepoCredentialsVolumeK8s(credentialsSecretNameOrEmpty: AllowLiteralOrExpression<string>) {
    return {
        volume: {
            name: S3_REPO_CREDENTIALS_VOLUME_NAME,
            secret: {
                secretName: makeStringTypeProxy(resolveName(credentialsSecretNameOrEmpty)),
                optional: makeDirectTypeProxy(expr.literal(true))
            }
        },
        volumeMount: {name: S3_REPO_CREDENTIALS_VOLUME_NAME, mountPath: S3_REPO_CREDENTIALS_MOUNT_PATH, readOnly: true}
    };
}
