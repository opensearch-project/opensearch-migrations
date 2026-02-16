import {
    BaseExpression,
    Container,
    expr,
    IMAGE_PULL_POLICY,
    makeDirectTypeProxy,
    makeStringTypeProxy,
    Volume
} from "@opensearch-migrations/argo-workflow-builders";

export type ContainerVolumePair = {
    readonly container: Container,
    readonly volumes: Volume[],
    readonly sidecars: Container[]
};

const S3_SNAPSHOT_VOLUME_NAME = "snapshot-s3";

export function setupS3MountpointVolumeForContainer(
    mountPath: string,
    s3RepoPathUri: BaseExpression<string>,
    s3Region: BaseExpression<string>,
    s3Endpoint: BaseExpression<string>,
    mountpointS3Image: BaseExpression<string>,
    mountpointS3ImagePullPolicy: BaseExpression<IMAGE_PULL_POLICY>,
    def: ContainerVolumePair): ContainerVolumePair {

    // Shell script parses S3_REPO_PATH_URI env var to extract bucket/prefix,
    // then runs mount-s3 with the appropriate args.
    const mountScript =
        `BUCKET=$(echo "$S3_REPO_PATH_URI" | sed 's|^s3://||' | cut -d/ -f1); ` +
        `PREFIX=$(echo "$S3_REPO_PATH_URI" | sed "s|^s3://$BUCKET/\\\\?||"); ` +
        `ARGS="$BUCKET ${mountPath} --read-only --foreground"; ` +
        `[ -n "$PREFIX" ] && ARGS="$ARGS --prefix $PREFIX/"; ` +
        `[ -n "$S3_REGION" ] && ARGS="$ARGS --region $S3_REGION"; ` +
        `[ -n "$S3_ENDPOINT" ] && ARGS="$ARGS --endpoint-url $S3_ENDPOINT"; ` +
        `exec mount-s3 $ARGS`;

    const sidecar: Container = {
        name: "mountpoint-s3",
        image: makeStringTypeProxy(mountpointS3Image),
        imagePullPolicy: makeStringTypeProxy(mountpointS3ImagePullPolicy),
        command: ["/bin/sh", "-c"],
        args: [mountScript],
        env: [
            { name: "S3_REPO_PATH_URI", value: makeStringTypeProxy(s3RepoPathUri) },
            { name: "S3_REGION", value: makeStringTypeProxy(s3Region) },
            { name: "S3_ENDPOINT", value: makeStringTypeProxy(s3Endpoint) }
        ],
        securityContext: {
            privileged: true
        },
        volumeMounts: [{
            name: S3_SNAPSHOT_VOLUME_NAME,
            mountPath: mountPath,
            mountPropagation: "Bidirectional"
        }]
    };

    const {volumeMounts, ...restOfContainer} = def.container;
    return {
        volumes: [
            ...def.volumes,
            {
                name: S3_SNAPSHOT_VOLUME_NAME,
                emptyDir: {}
            }
        ],
        container: {
            ...restOfContainer,
            volumeMounts: [
                ...(volumeMounts === undefined ? [] : volumeMounts),
                {
                    name: S3_SNAPSHOT_VOLUME_NAME,
                    mountPath: mountPath,
                    mountPropagation: "HostToContainer",
                    readOnly: true
                }
            ]
        },
        sidecars: [...def.sidecars, sidecar]
    } as const;
}

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
        sidecars: def.sidecars
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
                    name: "JAVA_OPTS",
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
        sidecars: def.sidecars
    } as const;
}
