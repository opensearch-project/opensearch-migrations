import {z} from "zod";
import {
    NAMED_TARGET_CLUSTER_CONFIG,
    ResourceRequirementsType, ARGO_REPLAYER_OPTIONS, ARGO_REPLAYER_WORKFLOW_OPTION_KEYS, KAFKA_CLIENT_CONFIG,
} from "@opensearch-migrations/schemas";
import {
    BaseExpression, Deployment,
    defineParam,
    defineRequiredParam,
    expr,
    IMAGE_PULL_POLICY,
    INTERNAL, makeDirectTypeProxy, makeStringTypeProxy,
    InputParamsToExpressions,
    selectInputsForRegister, Serialized,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {OwnerReference} from "@opensearch-migrations/k8s-types";
import {
    getTransformsPresence,
    setupLog4jConfigForContainer,
    setupTestCredsForContainer,
    setupTransformsForContainerForMode,
    TransformVolumeMode
} from "./commonUtils/containerFragments";
import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {getHttpAuthSecretName} from "./commonUtils/clusterSettingManipulators";
import {getTargetHttpAuthCredsEnvVars} from "./commonUtils/basicCredsGetters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {CONTAINER_NAMES} from "../containerNames";
import {K8S_RESOURCE_RETRY_STRATEGY} from "./commonUtils/resourceRetryStrategy";
import {ResourceManagement} from "./resourceManagement";

const KAFKA_AUTH_CONFIG_MOUNT_PATH = "/config/kafka-auth";
const KAFKA_AUTH_CONFIG_FILE_PATH = `${KAFKA_AUTH_CONFIG_MOUNT_PATH}/client.properties`;
const KAFKA_CA_MOUNT_PATH = "/config/kafka-ca";

function makeOwnerReferences(
    ownerName: BaseExpression<string>,
    ownerUid: BaseExpression<string>,
): OwnerReference[] {
    return [{
        apiVersion: "migrations.opensearch.org/v1alpha1",
        kind: "TrafficReplay",
        name: makeDirectTypeProxy(ownerName),
        uid: makeDirectTypeProxy(ownerUid),
        controller: true,
        blockOwnerDeletion: true,
    }];
}

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
                expr.makeDict({})
            ),
            expr.makeDict({})
        )
    );
}

function makeReplayerParamsDict(
    targetConfig: BaseExpression<Serialized<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>>,
    options: BaseExpression<Serialized<z.infer<typeof ARGO_REPLAYER_OPTIONS>>>,
    kafkaConfig: BaseExpression<Serialized<z.infer<typeof KAFKA_CLIENT_CONFIG>>>,
    kafkaGroupId: BaseExpression<string>,
    resolvedKafkaConnection: BaseExpression<string>,
    resolvedKafkaListenerName: BaseExpression<string>,
    resolvedKafkaAuthType: BaseExpression<string>,
) {
    const deserializedKafkaConfig = expr.deserializeRecord(kafkaConfig);
    const effectiveKafkaConnection = expr.ternary(
        expr.not(expr.equals(resolvedKafkaConnection, expr.literal(""))),
        resolvedKafkaConnection,
        expr.get(deserializedKafkaConfig, "kafkaConnection")
    );
    const effectiveKafkaListenerName = expr.ternary(
        expr.not(expr.equals(resolvedKafkaListenerName, expr.literal(""))),
        resolvedKafkaListenerName,
        expr.getLoose(deserializedKafkaConfig, "listenerName")
    );
    const effectiveKafkaAuthType = expr.ternary(
        expr.not(expr.equals(resolvedKafkaAuthType, expr.literal(""))),
        resolvedKafkaAuthType,
        expr.getLoose(deserializedKafkaConfig, "authType")
    );
    const effectiveKafkaSecretName = expr.getLoose(deserializedKafkaConfig, "secretName");
    const effectiveKafkaUserName = expr.getLoose(deserializedKafkaConfig, "kafkaUserName");
    const shouldUseScram = expr.equals(effectiveKafkaAuthType, expr.literal("scram-sha-512"));
    return expr.mergeDicts(
        expr.mergeDicts(
            makeReplayerTargetParamDict(targetConfig),
            expr.omit(expr.deserializeRecord(options), ...ARGO_REPLAYER_WORKFLOW_OPTION_KEYS)
        ),
        expr.mergeDicts(
            expr.makeDict({
                kafkaTrafficBrokers: effectiveKafkaConnection,
                kafkaTrafficTopic: expr.get(deserializedKafkaConfig, "kafkaTopic"),
                kafkaTrafficGroupId: kafkaGroupId,
                kafkaTrafficListenerName: effectiveKafkaListenerName,
                kafkaTrafficAuthType: effectiveKafkaAuthType,
                kafkaTrafficSecretName: effectiveKafkaSecretName,
                kafkaTrafficUserName: effectiveKafkaUserName,
            }),
            expr.ternary(
                shouldUseScram,
                expr.makeDict({
                    kafkaTrafficPropertyFile: expr.literal(KAFKA_AUTH_CONFIG_FILE_PATH),
                }),
                expr.makeDict({})
            )
        )
    );
}

function makeKafkaClientPropertiesConfigMap(name: BaseExpression<string>) {
    return {
        apiVersion: "v1",
        kind: "ConfigMap",
        metadata: {name},
        data: {
            "client.properties": [
                "ssl.truststore.type=PEM",
                `ssl.truststore.location=${KAFKA_CA_MOUNT_PATH}/ca.crt`,
            ].join("\n")
        }
    };
}


function getReplayerDeploymentManifest
(args: {
    workflowName: BaseExpression<string>,
    jsonConfig: BaseExpression<string>,
    name: BaseExpression<string>,

    useCustomLogging: BaseExpression<boolean>,
    useLocalStack: BaseExpression<boolean>,
    loggingConfigMap: BaseExpression<string>,
    jvmArgs: BaseExpression<string>,
    transformsImage: BaseExpression<string>,
    transformsImagePullPolicy: BaseExpression<IMAGE_PULL_POLICY>,
    transformsConfigMap: BaseExpression<string>,
    transformsVolumeMode: TransformVolumeMode,

    basicAuthSecretName: BaseExpression<string>,

    podReplicas: BaseExpression<number>,
    replayerImageName: BaseExpression<string>,
    replayerImagePullPolicy: BaseExpression<IMAGE_PULL_POLICY>,
    resources: BaseExpression<ResourceRequirementsType>,
    kafkaAuthConfigMapName: BaseExpression<string>,
    kafkaAuthType: BaseExpression<string>,
    kafkaSecretName: BaseExpression<string>,
    kafkaCaSecretName: BaseExpression<string>,
    ownerUid: BaseExpression<string>,
    sourceK8sLabel: BaseExpression<string>,
    targetK8sLabel: BaseExpression<string>,
    taskK8sLabel: BaseExpression<string>,
}): Deployment {
    const isScramAuth = expr.equals(args.kafkaAuthType, expr.literal("scram-sha-512"));
    const baseContainerDefinition = {
        name: CONTAINER_NAMES.REPLAYER,
        image: makeStringTypeProxy(args.replayerImageName),
        imagePullPolicy: makeStringTypeProxy(args.replayerImagePullPolicy),
        args: [
            "---INLINE-JSON",
            makeStringTypeProxy(args.jsonConfig)
        ],
        resources: makeDirectTypeProxy(args.resources),
        env: [
            {
                name: "TRAFFIC_REPLAYER_KAFKA_TRAFFIC_PASSWORD",
                valueFrom: {
                    secretKeyRef: {
                        name: makeStringTypeProxy(expr.ternary(
                            isScramAuth,
                            args.kafkaSecretName,
                            expr.literal("empty")
                        )),
                        key: "password",
                        optional: makeDirectTypeProxy(expr.not(isScramAuth))
                    }
                }
            },
            ...getTargetHttpAuthCredsEnvVars(args.basicAuthSecretName)
        ],
        volumeMounts: [
            {
                name: "kafka-auth-config",
                mountPath: KAFKA_AUTH_CONFIG_MOUNT_PATH,
                readOnly: true
            },
            {
                name: "kafka-ca",
                mountPath: KAFKA_CA_MOUNT_PATH,
                readOnly: true
            }
        ]
    };
    const finalContainerDefinition =
        setupTransformsForContainerForMode(args.transformsVolumeMode, args.transformsImage, args.transformsImagePullPolicy, args.transformsConfigMap,
            setupTestCredsForContainer(args.useLocalStack,
                setupLog4jConfigForContainer(args.useCustomLogging, args.loggingConfigMap,
                    {container: baseContainerDefinition, volumes: [
                    {
                        name: "kafka-auth-config",
                        configMap: {name: makeStringTypeProxy(args.kafkaAuthConfigMapName)}
                    },
                    {
                        name: "kafka-ca",
                        secret: {
                            secretName: makeStringTypeProxy(expr.ternary(
                                isScramAuth,
                                args.kafkaCaSecretName,
                                expr.literal("empty")
                            )),
                            optional: makeDirectTypeProxy(expr.not(isScramAuth))
                        }
                    }
                ]},
                args.jvmArgs))
        );
    return {
        apiVersion: "apps/v1",
        kind: "Deployment",
        metadata: {
            name: makeDirectTypeProxy(args.name),
            ownerReferences: makeOwnerReferences(args.name, args.ownerUid),
            labels: {
                app: "replayer",
                "workflows.argoproj.io/workflow": makeDirectTypeProxy(args.workflowName),
                "migrations.opensearch.org/source": makeStringTypeProxy(args.sourceK8sLabel),
                "migrations.opensearch.org/target": makeStringTypeProxy(args.targetK8sLabel),
                "migrations.opensearch.org/task": makeStringTypeProxy(args.taskK8sLabel),
            },
        },
        spec: {
            replicas: makeDirectTypeProxy(args.podReplicas),
            strategy: {
                type: "Recreate"
            },
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
                        "migrations.opensearch.org/source": makeStringTypeProxy(args.sourceK8sLabel),
                        "migrations.opensearch.org/target": makeStringTypeProxy(args.targetK8sLabel),
                        "migrations.opensearch.org/task": makeStringTypeProxy(args.taskK8sLabel),
                    },
                },
                spec: {
                    serviceAccountName: "argo-workflow-executor",
                    containers: [finalContainerDefinition.container],
                    volumes: finalContainerDefinition.volumes
                },
            },
        }
    }
}

const createDeploymentInputs = {
    name: defineRequiredParam<string>(),
    jsonConfig: defineRequiredParam<string>(),
    kafkaAuthConfigMapName: defineRequiredParam<string>(),
    kafkaAuthType: defineRequiredParam<string>(),
    kafkaSecretName: defineRequiredParam<string>(),
    kafkaCaSecretName: defineRequiredParam<string>(),
    ownerUid: defineRequiredParam<string>(),
    podReplicas: defineRequiredParam<number>(),
    useLocalStack: defineRequiredParam<boolean>(),
    jvmArgs: defineRequiredParam<string>(),
    loggingConfigurationOverrideConfigMap: defineRequiredParam<string>(),
    transformsImage: defineRequiredParam<string>(),
    transformsImagePullPolicy: defineRequiredParam<IMAGE_PULL_POLICY>(),
    transformsConfigMap: defineRequiredParam<string>(),
    basicAuthSecretName: defineRequiredParam<string>(),
    sourceK8sLabel: defineRequiredParam<string>(),
    targetK8sLabel: defineRequiredParam<string>(),
    taskK8sLabel: defineParam<string>({expression: expr.literal("trafficReplayer")}),
    ...makeRequiredImageParametersForKeys(["TrafficReplayer"]),
    resources: defineRequiredParam<ResourceRequirementsType>()
};

const replayerBaseBuilder = WorkflowBuilder.create({
    k8sResourceName: "replayer",
    serviceAccountName: "argo-workflow-executor",
}).addParams(CommonWorkflowParameters);

type CreateDeploymentInputExpressions = InputParamsToExpressions<typeof createDeploymentInputs>;

function makeReplayerDeploymentDefinition(
    inputs: CreateDeploymentInputExpressions,
    transformsVolumeMode: TransformVolumeMode
) {
    return {
        action: "apply" as const,
        setOwnerReference: false,
        successCondition: "status.readyReplicas > 0",
        manifest: getReplayerDeploymentManifest({
            podReplicas: expr.deserializeRecord(inputs.podReplicas),
            useCustomLogging: expr.not(expr.isEmpty(inputs.loggingConfigurationOverrideConfigMap)),
            useLocalStack: expr.deserializeRecord(inputs.useLocalStack),
            loggingConfigMap: inputs.loggingConfigurationOverrideConfigMap,
            jvmArgs: inputs.jvmArgs,
            transformsImage: inputs.transformsImage,
            transformsImagePullPolicy: inputs.transformsImagePullPolicy,
            transformsConfigMap: inputs.transformsConfigMap,
            transformsVolumeMode,
            basicAuthSecretName: inputs.basicAuthSecretName,
            name: inputs.name,
            replayerImageName: inputs.imageTrafficReplayerLocation,
            replayerImagePullPolicy: inputs.imageTrafficReplayerPullPolicy,
            workflowName: expr.getWorkflowValue("name"),
            jsonConfig: expr.toBase64(inputs.jsonConfig),
            resources: expr.deserializeRecord(inputs.resources),
            kafkaAuthConfigMapName: inputs.kafkaAuthConfigMapName,
            kafkaAuthType: inputs.kafkaAuthType,
            kafkaSecretName: inputs.kafkaSecretName,
            kafkaCaSecretName: inputs.kafkaCaSecretName,
            ownerUid: inputs.ownerUid,
            sourceK8sLabel: inputs.sourceK8sLabel,
            targetK8sLabel: inputs.targetK8sLabel,
            taskK8sLabel: inputs.taskK8sLabel,
        })
    };
}

function addReplayerTransformDeploymentTemplates(builder: typeof replayerBaseBuilder) {
    return builder
        .addTemplate("createDeploymentWithImageTransforms", (t) =>
            t
                .addInputsFromRecord(createDeploymentInputs)
                .addResourceTask(b => b
                    .setDefinition(makeReplayerDeploymentDefinition(b.inputs, "image")))
                .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
        )
        .addTemplate("createDeploymentWithConfigMapTransforms", (t) =>
            t
                .addInputsFromRecord(createDeploymentInputs)
                .addResourceTask(b => b
                    .setDefinition(makeReplayerDeploymentDefinition(b.inputs, "configMap")))
                .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
        )
        .addTemplate("createDeploymentNoTransforms", (t) =>
            t
                .addInputsFromRecord(createDeploymentInputs)
                .addResourceTask(b => b
                    .setDefinition(makeReplayerDeploymentDefinition(b.inputs, "emptyDir")))
                .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
        );
}

export const Replayer = addReplayerTransformDeploymentTemplates(replayerBaseBuilder)
  .addTemplate("createDeployment", (t) =>
    t
      .addInputsFromRecord(createDeploymentInputs)
      .addSteps(b => {
        const {hasImage, hasConfigMapOnly, hasNone} =
            getTransformsPresence(b.inputs.transformsImage, b.inputs.transformsConfigMap);

        return b
          .addStep("withImageTransforms", INTERNAL, "createDeploymentWithImageTransforms", c =>
              c.register({...selectInputsForRegister(b, c), taskK8sLabel: b.inputs.taskK8sLabel}),
              {when: {templateExp: hasImage}}
          )
          .addStep("withConfigMapTransforms", INTERNAL, "createDeploymentWithConfigMapTransforms", c =>
              c.register({...selectInputsForRegister(b, c), taskK8sLabel: b.inputs.taskK8sLabel}),
              {when: {templateExp: hasConfigMapOnly}}
          )
          .addStep("withoutTransforms", INTERNAL, "createDeploymentNoTransforms", c =>
              c.register({...selectInputsForRegister(b, c), taskK8sLabel: b.inputs.taskK8sLabel}),
              {when: {templateExp: hasNone}}
          );
      })
  )

  .addTemplate("createKafkaClientPropertiesConfigMap", (t) =>
    t
      .addRequiredInput("name", typeToken<string>())
      .addResourceTask((b) =>
        b.setDefinition({
          action: "apply",
          setOwnerReference: false,
          manifest: makeKafkaClientPropertiesConfigMap(b.inputs.name)
        }),
      )
      .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY),
  )

  .addTemplate("setupReplayer", (t) =>
    t
      .addRequiredInput(
        "kafkaConfig",
        typeToken<z.infer<typeof KAFKA_CLIENT_CONFIG>>(),
      )
      .addRequiredInput("kafkaGroupId", typeToken<string>())
      .addRequiredInput("name", typeToken<string>())
      .addRequiredInput("ownerUid", typeToken<string>())
      .addRequiredInput("sourceLabel", typeToken<string>())
      .addRequiredInput(
        "targetConfig",
        typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>(),
      )
      .addRequiredInput(
        "replayerOptions",
        typeToken<z.infer<typeof ARGO_REPLAYER_OPTIONS>>(),
      )
      .addRequiredInput("useLocalStack", typeToken<boolean>())
      .addOptionalInput("resolvedKafkaConnection", (c) => "")
      .addOptionalInput("resolvedKafkaListenerName", (c) => "")
      .addOptionalInput("resolvedKafkaAuthType", (c) => "")

      .addInputsFromRecord(
        makeRequiredImageParametersForKeys(["TrafficReplayer"]),
      )

      .addSteps((b) => {
        const kafkaConfig = expr.deserializeRecord(b.inputs.kafkaConfig);
        const effectiveKafkaAuthType = expr.ternary(
          expr.not(
            expr.equals(b.inputs.resolvedKafkaAuthType, expr.literal("")),
          ),
          b.inputs.resolvedKafkaAuthType,
          expr.getLoose(kafkaConfig, "authType"),
        );
        const kafkaAuthConfigMapName = expr.concat(
          b.inputs.name,
          expr.literal("-kafka-auth"),
        );
        return b
          .addStep(
            "createKafkaClientConfig",
            INTERNAL,
            "createKafkaClientPropertiesConfigMap",
            (c) =>
              c.register({
                name: kafkaAuthConfigMapName,
              }),
          )
          .addStep("deployReplayer", INTERNAL, "createDeployment", (c) =>
            c.register({
              ...selectInputsForRegister(b, c),
              kafkaAuthConfigMapName,
              kafkaAuthType: effectiveKafkaAuthType,
              kafkaSecretName: expr.getLoose(kafkaConfig, "secretName"),
              kafkaCaSecretName: expr.getLoose(kafkaConfig, "caSecretName"),
              ownerUid: b.inputs.ownerUid,
              podReplicas: expr.dig(
                expr.deserializeRecord(b.inputs.replayerOptions),
                ["podReplicas"],
                1,
              ),
              useLocalStack: b.inputs.useLocalStack,
              jvmArgs: expr.dig(
                expr.deserializeRecord(b.inputs.replayerOptions),
                ["jvmArgs"],
                "",
              ),
              loggingConfigurationOverrideConfigMap: expr.dig(
                expr.deserializeRecord(b.inputs.replayerOptions),
                ["loggingConfigurationOverrideConfigMap"],
                "",
              ),
              transformsImage: expr.dig(
                expr.deserializeRecord(b.inputs.replayerOptions),
                ["transformsImage"],
                "",
              ),
              transformsImagePullPolicy: expr.get(
                expr.deserializeRecord(b.inputs.replayerOptions),
                "transformsImagePullPolicy",
              ),
              transformsConfigMap: expr.dig(
                expr.deserializeRecord(b.inputs.replayerOptions),
                ["transformsConfigMap"],
                "",
              ),
              basicAuthSecretName: getHttpAuthSecretName(b.inputs.targetConfig),
              jsonConfig: expr.asString(
                expr.serialize(
                  makeReplayerParamsDict(
                    b.inputs.targetConfig,
                    b.inputs.replayerOptions,
                    b.inputs.kafkaConfig,
                    b.inputs.kafkaGroupId,
                    b.inputs.resolvedKafkaConnection,
                    b.inputs.resolvedKafkaListenerName,
                    b.inputs.resolvedKafkaAuthType,
                  ),
                ),
              ),
              resources: expr.serialize(
                expr.jsonPathStrict(b.inputs.replayerOptions, "resources"),
              ),
              sourceK8sLabel: b.inputs.sourceLabel,
              targetK8sLabel: expr.jsonPathStrict(b.inputs.targetConfig, "label"),
            }),
          );
      }),
  )

  .getFullScope();
