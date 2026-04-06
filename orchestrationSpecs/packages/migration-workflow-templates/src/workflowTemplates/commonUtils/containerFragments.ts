import {
    BaseExpression,
    Container,
    expr,
    makeDirectTypeProxy,
    makeStringTypeProxy,
    Volume
} from "@opensearch-migrations/argo-workflow-builders";

export type ContainerVolumePair = {
    readonly container: Container,
    readonly volumes: Volume[],
    readonly sidecars: Container[],
    readonly initContainers: Container[]
};

export function setupTestCredsForContainer(
    useLocalStack: BaseExpression<boolean>,
    def: ContainerVolumePair): ContainerVolumePair {
    const TEST_CREDS_VOLUME_NAME = "localstack-test-creds";
    const {volumeMounts, env, ...restOfContainer} = def.container;
    return {
        volumes: [
            ...def.volumes,
            {
                name: TEST_CREDS_VOLUME_NAME,
                configMap: {
                    name: makeStringTypeProxy(expr.literal("localstack-test-creds")),
                    optional: makeDirectTypeProxy(expr.not(useLocalStack))
                }
            }
        ],
        container: {
            ...restOfContainer,
            env: [
                ...(env === undefined ? [] : env),
                {
                    name: "AWS_SHARED_CREDENTIALS_FILE",
                    value: makeStringTypeProxy(expr.ternary(useLocalStack, expr.literal("/config/credentials/configuration"), expr.literal("")))
                }
            ],
            volumeMounts: [
                ...(volumeMounts === undefined ? [] : volumeMounts),
                {
                    name: TEST_CREDS_VOLUME_NAME,
                    mountPath: "/config/credentials",
                    readOnly: true
                }
            ]
        },
        sidecars: def.sidecars,
        initContainers: def.initContainers
    } as const;
}

const DEFAULT_LOGGING_CONFIGURATION_CONFIGMAP_NAME = "default-log4j-config";

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
 * evaluates to true and the latter is empty, "default-log4j-config" is used.  Notice that the
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
    existingJavaOpts: BaseExpression<string> = expr.literal(" ")): ContainerVolumePair {
    const {volumeMounts, env, ...restOfContainer} = def.container;
    const LOG4J_CONFIG_VOLUME_NAME = "log4j-configuration";
    return {
        volumes: [
            ...def.volumes,
            {
                name: LOG4J_CONFIG_VOLUME_NAME,
                configMap: {
                    name: makeStringTypeProxy(expr.ternary(
                        expr.isEmpty(loggingConfigMapName),
                        expr.literal(DEFAULT_LOGGING_CONFIGURATION_CONFIGMAP_NAME),
                        loggingConfigMapName)),
                    optional: makeDirectTypeProxy(expr.not(customLoggingEnabled))
                }
            }
        ],
        container: {
            ...restOfContainer,
            env: [
                ...(env === undefined ? [] : env),
                {
                    name: "JDK_JAVA_OPTIONS",
                    value:
                        makeStringTypeProxy(expr.concatWith(" ",
                                existingJavaOpts,
                                expr.ternary(
                                    customLoggingEnabled,
                                    expr.literal("-Dlog4j2.configurationFile=/config/logConfiguration"),
                                    expr.literal("")),
                            )
                        )
                }
            ],
            volumeMounts: [
                ...(volumeMounts === undefined ? [] : volumeMounts),
                {
                    name: LOG4J_CONFIG_VOLUME_NAME,
                    mountPath: "/config/logConfiguration",
                    readOnly: true
                }
            ]
        },
        sidecars: def.sidecars,
        initContainers: def.initContainers
    } as const;
}

/**
 * Add a mount-s3 native sidecar (init container with restartPolicy: Always) that
 * mounts an S3 bucket at a specified path for read-write access.
 *
 * Used by the replayer to write tuple files directly to S3 via Mountpoint S3.
 * Mountpoint S3 supports sequential writes with one writer per file and fsync
 * flushes multipart upload parts — matching GzipJsonLinesSink's write pattern.
 *
 * As a native sidecar (K8s 1.29+), kubelet guarantees it starts and passes its
 * startupProbe before any regular containers start.
 *
 * Uses a hostPath volume for the mount point to avoid SELinux relabeling failures.
 * Creates per-pod subdirectories (using POD_NAME) inside the hostPath.
 */
export function setupS3MountpointVolumeForContainer(
    s3Bucket: BaseExpression<string>,
    s3Region: BaseExpression<string>,
    s3Prefix: BaseExpression<string>,
    mountPath: string,
    useLocalStack: BaseExpression<boolean>,
    def: ContainerVolumePair): ContainerVolumePair {

    const SHARED_HOSTPATH_VOLUME = "s3-tuple-mnt";
    const {volumeMounts, env, ...restOfContainer} = def.container;
    const podNameEnv = {
        name: "POD_NAME",
        valueFrom: { fieldRef: { fieldPath: "metadata.name" } }
    };
    return {
        volumes: [
            ...def.volumes,
            { name: SHARED_HOSTPATH_VOLUME, hostPath: { path: "/var/tmp/s3-tuple-mnt", type: "DirectoryOrCreate" } }
        ],
        container: {
            ...restOfContainer,
            env: [
                ...(env === undefined ? [] : env),
                podNameEnv
            ],
            volumeMounts: [
                ...(volumeMounts === undefined ? [] : volumeMounts),
                {
                    name: SHARED_HOSTPATH_VOLUME,
                    mountPath: mountPath,
                    mountPropagation: "HostToContainer"
                }
            ]
        },
        sidecars: def.sidecars,
        initContainers: [
            ...def.initContainers,
            {
                name: "mount-s3-tuples",
                restartPolicy: "Always",
                image: "public.ecr.aws/mountpoint-s3/aws-mountpoint-s3-csi-driver:latest",
                command: ["sh", "-c"],
                args: [
                    "P=/mnt/.pods/${POD_NAME}; " +
                    "mkdir -p $P/s3; " +
                    "mount-s3 ${S3_BUCKET} $P/s3 " +
                    "--prefix=${S3_PREFIX} " +
                    "--allow-delete --allow-overwrite " +
                    "--dir-mode=0777 --file-mode=0666 " +
                    "${S3_ENDPOINT_FLAG} ${S3_FORCE_PATH_STYLE_FLAG} " +
                    "--foreground"
                ],
                startupProbe: {
                    exec: {
                        command: ["sh", "-c", "mountpoint -q /mnt/.pods/${POD_NAME}/s3"]
                    },
                    initialDelaySeconds: 1,
                    periodSeconds: 2,
                    failureThreshold: 30
                },
                env: [
                    podNameEnv,
                    { name: "S3_BUCKET", value: makeStringTypeProxy(s3Bucket) },
                    { name: "S3_PREFIX", value: makeStringTypeProxy(s3Prefix) },
                    { name: "AWS_REGION", value: makeStringTypeProxy(s3Region) },
                    {
                        name: "S3_ENDPOINT_FLAG",
                        value: makeStringTypeProxy(expr.ternary(
                            useLocalStack,
                            expr.literal("--endpoint-url=http://localstack:4566"),
                            expr.literal("")
                        ))
                    },
                    {
                        name: "S3_FORCE_PATH_STYLE_FLAG",
                        value: makeStringTypeProxy(expr.ternary(
                            useLocalStack,
                            expr.literal("--force-path-style"),
                            expr.literal("")
                        ))
                    },
                    {
                        name: "AWS_SHARED_CREDENTIALS_FILE",
                        value: makeStringTypeProxy(expr.ternary(
                            useLocalStack,
                            expr.literal("/config/credentials/configuration"),
                            expr.literal("")
                        ))
                    }
                ],
                securityContext: { privileged: true },
                volumeMounts: [
                    {
                        name: SHARED_HOSTPATH_VOLUME,
                        mountPath: "/mnt",
                        mountPropagation: "Bidirectional"
                    },
                    {
                        name: "localstack-test-creds",
                        mountPath: "/config/credentials",
                        readOnly: true
                    }
                ]
            }
        ]
    } as const;
}