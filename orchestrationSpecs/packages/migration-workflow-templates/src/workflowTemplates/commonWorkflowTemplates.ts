import {z} from "zod";
import {TARGET_CLUSTER_CONFIG} from "@opensearch-migrations/schemas";
import {
    BaseExpression,
    defineParam,
    defineRequiredParam,
    expr,
    IMAGE_PULL_POLICY,
    InputParamDef,
    Serialized
} from "@opensearch-migrations/argo-workflow-builders";

export const CommonWorkflowParameters = {
    etcdEndpoints: defineParam({expression: "http://etcd.ma.svc.cluster.local:2379"}),
    etcdUser: defineParam({expression: "root"}),
    etcdPassword: defineParam({expression: "password"}),
    s3SnapshotConfigMap: defineParam({expression: "s3-snapshot-config"}),
    imageConfigMapName: defineParam({expression: "migration-image-config"})
} as const;

export const LogicalOciImages = [
    "CaptureProxy",
    "TrafficReplayer",
    "ReindexFromSnapshot",
    "MigrationConsole",
    "EtcdUtils",
] as const;
export type LogicalOciImagesKeys = typeof LogicalOciImages[number];

export function makeRequiredImageParametersForKeys<K extends LogicalOciImagesKeys, T extends readonly K[]>(keys: T) {
    return Object.fromEntries(
        keys.flatMap(k => [
            [`image${k}Location`, defineRequiredParam<string>()],
            [`image${k}PullPolicy`, defineRequiredParam<IMAGE_PULL_POLICY>()]
        ])
    ) as Record<`image${typeof keys[number]}Location`, InputParamDef<string, true>> &
        Record<`image${typeof keys[number]}PullPolicy`, InputParamDef<IMAGE_PULL_POLICY, true>>;
}

export const ImageParameters = makeRequiredImageParametersForKeys(LogicalOciImages);

export const TargetClusterParameters = {
    targetAwsRegion: defineRequiredParam<string>(),
    targetAwsSigningName: defineRequiredParam<string>(),
    targetCACert: defineRequiredParam<string>(),
    targetClientSecretName: defineRequiredParam<string>(), // TODO
    targetInsecure: defineRequiredParam<boolean>(),
    targetUsername: defineRequiredParam<string>(),
    targetPassword: defineRequiredParam<string>()
}

export function extractTargetKeysToExpressionMap(targetConfig: BaseExpression<Serialized<z.infer<typeof TARGET_CLUSTER_CONFIG>>>) {
    return {
        targetAwsRegion:
            expr.dig(expr.deserializeRecord(targetConfig), ["authConfig", "sigv4", "region"], ""),
        targetAwsSigningName:
            expr.dig(expr.deserializeRecord(targetConfig), ["authConfig", "sigv4", "service"], ""),
        targetCACert:
            expr.dig(expr.deserializeRecord(targetConfig), ["authConfig", "mtls", "caCert"], ""),
        targetClientSecretName:
            expr.dig(expr.deserializeRecord(targetConfig), ["authConfig", "mtls", "clientSecretName"], ""),
        targetInsecure:
            expr.dig(expr.deserializeRecord(targetConfig), ["allowInsecure"], false),
        targetUsername:
            expr.dig(expr.deserializeRecord(targetConfig), ["authConfig", "basic", "username"], ""),
        targetPassword:
            expr.dig(expr.deserializeRecord(targetConfig), ["authConfig", "basic", "password"], ""),
    };
}

export function safeSpread<T>(list: T[]) {
    return list === undefined ? [] : list;
}

export type ContainerVolumePair = { container: Record<string, any>, volumes: Record<string, any>[]};

export function setupTestCredsForContainer(
    useLocalStack: BaseExpression<boolean>,
    def: ContainerVolumePair)
{
    const TEST_CREDS_VOLUME_NAME = "localstack-test-creds";
    const {volumeMounts, env, ...restOfContainer} = def.container;
    return {
        volumes: [
            ...def.volumes,
            {
                name: TEST_CREDS_VOLUME_NAME,
                configMap: {
                    name: expr.literal("localstack-test-creds"),
                    optional: expr.not(useLocalStack)
                }
            }
        ],
        container: {
            ...restOfContainer,
            env: [
                ...safeSpread(env),
                {
                    name: "AWS_SHARED_CREDENTIALS_FILE",
                    value: expr.ternary(useLocalStack, expr.literal("/config/credentials"), expr.literal(""))
                }
            ],
            volumeMounts: [
                ...safeSpread(volumeMounts),
                {
                    name: TEST_CREDS_VOLUME_NAME,
                    mountPath: "/config/credentials",
                    readOnly: true
                }
            ]
        }
    }
}

const DEFAULT_LOGGING_CONFIGURATION_CONFIGMAP_NAME = "default-logging-configuration";

/**
 * The log4j2 library interprets an empty file not as a missing configuration, but a no-logging configuration.
 * That means that we can't blindly set the configurationFile property to an empty file, but need to avoid
 * setting it altogether.  That's done w/ a level of indirection through the JAVA_OPTS.
 *
 * There isn't a config map configured by default since each application keeps its own default logging properties
 * within its jarfile.  Since logging configurations are highly specific and variable between applications,
 * fine-grained granularity should be provided.  That's why the caller passes a specific configmap name into
 * this resource constructor.  However, when the configmap isn't specified at Argo runtime, this specification
 * needs to provide something for Argo to do.  We can use an expression to control if the passed configmap should
 * be required or optionally mounted.
 *
 * However, when there's no configmap specified at all, we need to specify to argo SOME configuration to use,
 * even if it doesn't exist.  If the name of the configmap is empty, even when optional=true, Argo will throw
 * an error and not even load the template.  While we can/will wire up the java property to do nothing,
 * letting the application fallback to its builtin logging configuration, we still need to specify SOME logging
 * configmap name.
 *
 * That brings us to the somewhat awkward requirement that this function takes in TWO different flags instead
 * of just one.  One flag sets (customLoggingEnabled) up the property to use what should be a non-empty
 * configuration file and the other (loggingConfigMapName) specifies the name of that configmap.  If the former
 * evaluates to true and the latter is empty, "default-logging-configuration" is used.  Notice that the
 * migration-assistant helm chart doesn't create that configmap, and it's fine if it doesn't exist.
 *
 * Because of the specificity mentioned above, many callers/resources may be wired up to not even enable the
 * path where the default configmap's contents would actually be used as the log4j configuration - but we still
 * need the resource configuration here to keep Argo and K8s happy.
 */
export function setupLog4jConfigForContainer(
    customLoggingEnabled: BaseExpression<boolean>,
    loggingConfigMapName: BaseExpression<string>,
    def: ContainerVolumePair,
    existingJavaOpts: BaseExpression<string>=expr.literal(" "))
{
    const {volumeMounts, env, ...restOfContainer} = def.container;
    const LOG4J_CONFIG_VOLUME_NAME = "log4j-configuration";
    return {
        volumes: [
            ...def.volumes,
            {
                name: LOG4J_CONFIG_VOLUME_NAME,
                configMap: {
                    name: expr.ternary(
                        expr.equals(expr.literal(""),
                            expr.nullCoalesce(loggingConfigMapName, expr.literal(""))),
                        expr.literal(DEFAULT_LOGGING_CONFIGURATION_CONFIGMAP_NAME),
                        loggingConfigMapName),
                    optional: expr.not(customLoggingEnabled)
                }
            }
        ],
        container: {
            ...restOfContainer,
            env: [
                ...safeSpread(env),
                {
                    name: "JAVA_OPTS",
                    value:
                        expr.concatWith(" ",
                            existingJavaOpts,
                            expr.ternary(
                                customLoggingEnabled,
                                expr.literal("-Dlog4j2.configurationFile=/config/logConfiguration"),
                                expr.literal("")),
                            )
                }
            ],
            volumeMounts: [
                ...safeSpread(volumeMounts),
                {
                    name: LOG4J_CONFIG_VOLUME_NAME,
                    mountPath: "/config/logConfiguration",
                    readOnly: true
                }
            ]
        }
    }
}
