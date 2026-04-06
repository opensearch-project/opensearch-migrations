import {z} from "zod";
import {
    NAMED_TARGET_CLUSTER_CONFIG,
    USER_REPLAYER_OPTIONS,
    ResourceRequirementsType, ARGO_REPLAYER_OPTIONS, ARGO_REPLAYER_WORKFLOW_OPTION_KEYS, KAFKA_CLIENT_CONFIG,
} from "@opensearch-migrations/schemas";
import {
    BaseExpression, Deployment,
    expr,
    IMAGE_PULL_POLICY,
    INTERNAL, makeDirectTypeProxy, makeStringTypeProxy,
    selectInputsForRegister, Serialized,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {setupLog4jConfigForContainer, setupS3MountpointVolumeForContainer, setupTestCredsForContainer} from "./commonUtils/containerFragments";
import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {getHttpAuthSecretName} from "./commonUtils/clusterSettingManipulators";
import {getTargetHttpAuthCredsEnvVars} from "./commonUtils/basicCredsGetters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {K8S_RESOURCE_RETRY_STRATEGY} from "./commonUtils/resourceRetryStrategy";

const S3_TUPLE_MOUNT_PATH = "/mnt/s3/tuples";

function makeReplayerTargetParamDict(
    targetConfig: BaseExpression<Serialized<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>>
) {
    const tc = expr.deserializeRecord(targetConfig);
    const safeAuth = expr.getLoose(tc, "authConfig");
    return expr.mergeDicts(
        expr.makeDict({
            targetUri: expr.get(tc, "endpoint"),
            insecure: expr.dig(expr.deserializeRecord(targetConfig), ["allowInsecure"], false),
        }),
        expr.ternary(
            expr.hasKey(tc, "authConfig"),
            expr.ternary(
                expr.hasKey(safeAuth, "sigv4"),
                expr.makeDict({
                    sigv4AuthHeaderServiceRegion: expr.concat(
                        expr.getLoose(expr.getLoose(safeAuth, "sigv4"), "service"),
                        expr.literal(","),
                        expr.getLoose(expr.getLoose(safeAuth, "sigv4"), "region")
                    )
                }),
                expr.literal({})
            ),
            expr.literal({})
        )
    );
}

function makeReplayerParamsDict(
    targetConfig: BaseExpression<Serialized<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>>,
    options: BaseExpression<Serialized<z.infer<typeof USER_REPLAYER_OPTIONS>>>,
    kafkaConfig: BaseExpression<Serialized<z.infer<typeof KAFKA_CLIENT_CONFIG>>>,
    kafkaGroupId: BaseExpression<string>,
    tupleS3Bucket: BaseExpression<string>,
) {
    const base = expr.mergeDicts(
        expr.mergeDicts(
            makeReplayerTargetParamDict(targetConfig),
            expr.omit(expr.deserializeRecord(options), ...ARGO_REPLAYER_WORKFLOW_OPTION_KEYS)
        ),
        expr.makeDict({
            kafkaTrafficBrokers: expr.get(expr.deserializeRecord(kafkaConfig), "kafkaConnection"),
            kafkaTrafficTopic: expr.get(expr.deserializeRecord(kafkaConfig), "kafkaTopic"),
            kafkaTrafficGroupId: kafkaGroupId,
        })
    );
    // When S3 bucket is configured, override tupleOutputDir to the mount path
    return expr.mergeDicts(
        base,
        expr.ternary(
            expr.isEmpty(tupleS3Bucket),
            expr.makeDict({}),
            expr.makeDict({ tupleOutputDir: expr.literal(S3_TUPLE_MOUNT_PATH) })
        )
    );
}


function getReplayerDeploymentManifest
(args: {
    workflowName: BaseExpression<string>,
    jsonConfig: BaseExpression<string>,
    name: BaseExpression<string>,

    useCustomLogging: BaseExpression<boolean>,
    loggingConfigMap: BaseExpression<string>,
    jvmArgs: BaseExpression<string>,

    basicAuthSecretName: BaseExpression<string>,

    podReplicas: BaseExpression<number>,
    replayerImageName: BaseExpression<string>,
    replayerImagePullPolicy: BaseExpression<IMAGE_PULL_POLICY>,
    resources: BaseExpression<ResourceRequirementsType>,

    tupleS3Bucket: BaseExpression<string>,
    tupleS3Region: BaseExpression<string>,
    tupleS3Prefix: BaseExpression<string>,
    useLocalStack: BaseExpression<boolean>,
}): Deployment {
    const baseContainerDefinition = {
        name: "replayer",
        image: makeStringTypeProxy(args.replayerImageName),
        imagePullPolicy: makeStringTypeProxy(args.replayerImagePullPolicy),
        args: [
            "---INLINE-JSON",
            makeStringTypeProxy(args.jsonConfig)
        ],
        resources: makeDirectTypeProxy(args.resources),
        env: [
            ...getTargetHttpAuthCredsEnvVars(args.basicAuthSecretName),
        ]
    };
    const withLog4j = setupLog4jConfigForContainer(args.useCustomLogging, args.loggingConfigMap,
        {container: baseContainerDefinition, volumes: [], sidecars: [], initContainers: []},
        args.jvmArgs);
    const withS3 = setupS3MountpointVolumeForContainer(
        args.tupleS3Bucket,
        args.tupleS3Region,
        args.tupleS3Prefix,
        S3_TUPLE_MOUNT_PATH,
        args.useLocalStack,
        withLog4j);
    const finalContainerDefinition = setupTestCredsForContainer(args.useLocalStack, withS3);
    return {
        apiVersion: "apps/v1",
        kind: "Deployment",
        metadata: {
            name: makeDirectTypeProxy(args.name),
            labels: {
                app: "replayer",
                "workflows.argoproj.io/workflow": makeDirectTypeProxy(args.workflowName)
            },
        },
        spec: {
            replicas: makeDirectTypeProxy(args.podReplicas),
            selector: {
                matchLabels: {
                    app: "replayer",
                },
            },
            template: {
                metadata: {
                    labels: {
                        app: "replayer",
                        "workflows.argoproj.io/workflow": makeDirectTypeProxy(args.workflowName),
                    },
                },
                spec: {
                    serviceAccountName: "argo-workflow-executor",
                    securityContext: { seLinuxOptions: { type: "spc_t" } },
                    initContainers: [...finalContainerDefinition.initContainers],
                    containers: [finalContainerDefinition.container, ...finalContainerDefinition.sidecars],
                    volumes: [...finalContainerDefinition.volumes]
                },
            },
        }
    }
}


export const Replayer = WorkflowBuilder.create({
    k8sResourceName: "replayer",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("createDeployment", t => t
        .addRequiredInput("name", typeToken<string>())
        .addRequiredInput("jsonConfig", typeToken<string>())
        .addRequiredInput("podReplicas", typeToken<number>())
        .addRequiredInput("jvmArgs", typeToken<string>())
        .addRequiredInput("loggingConfigurationOverrideConfigMap", typeToken<string>())
        .addRequiredInput("basicAuthSecretName", typeToken<string>())
        .addRequiredInput("useLocalStack", typeToken<boolean>())
        .addRequiredInput("tupleS3Bucket", typeToken<string>())
        .addRequiredInput("tupleS3Region", typeToken<string>())
        .addRequiredInput("tupleS3Prefix", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["TrafficReplayer"]))
        .addRequiredInput("resources", typeToken<ResourceRequirementsType>())

        .addResourceTask(b => b
            .setDefinition({
                action: "create",
                setOwnerReference: true,
                manifest: getReplayerDeploymentManifest({
                    podReplicas: expr.deserializeRecord(b.inputs.podReplicas),
                    useCustomLogging: expr.not(expr.equals(expr.literal(""), b.inputs.loggingConfigurationOverrideConfigMap)),
                    loggingConfigMap: b.inputs.loggingConfigurationOverrideConfigMap,
                    jvmArgs: b.inputs.jvmArgs,
                    basicAuthSecretName: b.inputs.basicAuthSecretName,
                    name: b.inputs.name,
                    replayerImageName: b.inputs.imageTrafficReplayerLocation,
                    replayerImagePullPolicy: b.inputs.imageTrafficReplayerPullPolicy,
                    workflowName: expr.getWorkflowValue("name"),
                    jsonConfig: expr.toBase64(b.inputs.jsonConfig),
                    resources: expr.deserializeRecord(b.inputs.resources),
                    tupleS3Bucket: b.inputs.tupleS3Bucket,
                    tupleS3Region: b.inputs.tupleS3Region,
                    tupleS3Prefix: b.inputs.tupleS3Prefix,
                    useLocalStack: expr.deserializeRecord(b.inputs.useLocalStack),
                })
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("setupReplayer", t => t
        .addRequiredInput("kafkaConfig", typeToken<z.infer<typeof KAFKA_CLIENT_CONFIG>>())
        .addRequiredInput("kafkaGroupId", typeToken<string>())
        .addRequiredInput("name", typeToken<string>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("replayerOptions", typeToken<z.infer<typeof ARGO_REPLAYER_OPTIONS>>())

        .addInputsFromRecord(makeRequiredImageParametersForKeys(["TrafficReplayer"]))

        .addSteps(b => b
            .addStep("deployReplayer", INTERNAL, "createDeployment", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    podReplicas: expr.dig(expr.deserializeRecord(b.inputs.replayerOptions), ["podReplicas"], 1),
                    jvmArgs: expr.dig(expr.deserializeRecord(b.inputs.replayerOptions), ["jvmArgs"], ""),
                    loggingConfigurationOverrideConfigMap: expr.dig(expr.deserializeRecord(b.inputs.replayerOptions), ["loggingConfigurationOverrideConfigMap"], ""),
                    basicAuthSecretName: getHttpAuthSecretName(b.inputs.targetConfig),
                    tupleS3Bucket: expr.dig(expr.deserializeRecord(b.inputs.replayerOptions), ["tupleS3Bucket"], ""),
                    tupleS3Region: expr.dig(expr.deserializeRecord(b.inputs.replayerOptions), ["tupleS3Region"], ""),
                    tupleS3Prefix: expr.dig(expr.deserializeRecord(b.inputs.replayerOptions), ["tupleS3Prefix"], "tuples/"),
                    useLocalStack: expr.serialize(expr.dig(expr.deserializeRecord(b.inputs.replayerOptions), ["useLocalStack"], false)),
                    jsonConfig: expr.asString(expr.serialize(
                        makeReplayerParamsDict(
                            b.inputs.targetConfig,
                            b.inputs.replayerOptions,
                            b.inputs.kafkaConfig,
                            b.inputs.kafkaGroupId,
                            expr.dig(expr.deserializeRecord(b.inputs.replayerOptions), ["tupleS3Bucket"], "")
                        ) as any
                    )),
                    resources: expr.serialize(expr.jsonPathStrict(b.inputs.replayerOptions, "resources"))

                })))
    )


    .getFullScope();
