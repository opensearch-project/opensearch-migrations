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
    readonly volumes: Volume[]
};

export const TRANSFORMS_MOUNT_PATH = "/transforms";
const TRANSFORMS_VOLUME_NAME = "user-transforms";
export type TransformVolumeMode = "image" | "configMap" | "emptyDir";

export function getTransformsPresence(
    transformsImage: BaseExpression<string>,
    transformsConfigMap: BaseExpression<string>
) {
    const hasImage = expr.not(expr.isEmpty(transformsImage));
    const hasConfigMap = expr.not(expr.isEmpty(transformsConfigMap));
    return {
        hasImage,
        hasConfigMap,
        hasAny: expr.or(hasImage, hasConfigMap),
        hasConfigMapOnly: expr.and(expr.not(hasImage), hasConfigMap),
        hasNone: expr.and(expr.not(hasImage), expr.not(hasConfigMap)),
    } as const;
}

export function makeTransformsVolume(
    transformsImage: BaseExpression<string>,
    transformsImagePullPolicy: BaseExpression<IMAGE_PULL_POLICY>,
    transformsConfigMap: BaseExpression<string>
) {
    const {hasImage, hasConfigMap} = getTransformsPresence(transformsImage, transformsConfigMap);
    const imageVolume = expr.makeDict({
        name: TRANSFORMS_VOLUME_NAME,
        image: expr.makeDict({
            reference: transformsImage,
            pullPolicy: transformsImagePullPolicy,
        }),
    }) as BaseExpression<any>;
    const configMapVolume = expr.makeDict({
        name: TRANSFORMS_VOLUME_NAME,
        configMap: expr.makeDict({
            name: transformsConfigMap,
        }),
    }) as BaseExpression<any>;
    const emptyDirVolume = expr.makeDict({
        name: TRANSFORMS_VOLUME_NAME,
        emptyDir: expr.makeDict({}),
    }) as BaseExpression<any>;

    return expr.ternary(
        hasImage,
        imageVolume,
        expr.ternary(
            hasConfigMap,
            configMapVolume,
            emptyDirVolume
        )
    );
}

function makeTransformsVolumeForMode(
    mode: TransformVolumeMode,
    transformsImage: BaseExpression<string>,
    transformsImagePullPolicy: BaseExpression<IMAGE_PULL_POLICY>,
    transformsConfigMap: BaseExpression<string>
): Volume {
    if (mode === "image") {
        return {
            name: TRANSFORMS_VOLUME_NAME,
            image: {
                reference: makeStringTypeProxy(transformsImage),
                pullPolicy: makeStringTypeProxy(transformsImagePullPolicy)
            }
        };
    }

    if (mode === "configMap") {
        return {
            name: TRANSFORMS_VOLUME_NAME,
            configMap: {
                name: makeStringTypeProxy(transformsConfigMap)
            }
        };
    }

    return {
        name: TRANSFORMS_VOLUME_NAME,
        emptyDir: {}
    };
}

export function setupTransformsForContainerForMode(
    mode: TransformVolumeMode,
    transformsImage: BaseExpression<string>,
    transformsImagePullPolicy: BaseExpression<IMAGE_PULL_POLICY>,
    transformsConfigMap: BaseExpression<string>,
    def: ContainerVolumePair): ContainerVolumePair {
    const {volumeMounts, ...restOfContainer} = def.container;
    return {
        volumes: [
            ...def.volumes,
            makeTransformsVolumeForMode(mode, transformsImage, transformsImagePullPolicy, transformsConfigMap)
        ],
        container: {
            ...restOfContainer,
            volumeMounts: [
                ...(volumeMounts === undefined ? [] : volumeMounts),
                {
                    name: TRANSFORMS_VOLUME_NAME,
                    mountPath: TRANSFORMS_MOUNT_PATH,
                    readOnly: true
                }
            ]
        }
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
        }
    } as const;
}

const DEFAULT_LOGGING_CONFIGURATION_CONFIGMAP_NAME = "default-log4j-config";
const LOG4J_CONFIG_MOUNT_PATH = "/config/logConfiguration";
const LOG4J_CONFIG_FILE_PATH = `${LOG4J_CONFIG_MOUNT_PATH}/configuration`;

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
                                    expr.literal(`-Dlog4j2.configurationFile=${LOG4J_CONFIG_FILE_PATH}`),
                                    expr.literal("")),
                            )
                        )
                }
            ],
            volumeMounts: [
                ...(volumeMounts === undefined ? [] : volumeMounts),
                {
                    name: LOG4J_CONFIG_VOLUME_NAME,
                    mountPath: LOG4J_CONFIG_MOUNT_PATH,
                    readOnly: true
                }
            ]
        }
    } as const;
}
