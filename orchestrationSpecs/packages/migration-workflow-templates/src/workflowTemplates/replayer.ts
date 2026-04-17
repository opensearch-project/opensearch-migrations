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
import {OwnerReference} from "@opensearch-migrations/k8s-types";
import {setupLog4jConfigForContainer} from "./commonUtils/containerFragments";
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

function makeTrafficReplayManifest(
    name: BaseExpression<string>,
    dependsOn: BaseExpression<Serialized<string[]>>,
    replayerOptions: BaseExpression<Serialized<z.infer<typeof ARGO_REPLAYER_OPTIONS>>>,
) {
    const opts = expr.deserializeRecord(replayerOptions);
    const workflowSpecFields = expr.makeDict({
        dependsOn: expr.deserializeRecord(dependsOn),
        jvmArgs: expr.dig(opts, ["jvmArgs"], expr.literal("")),
        loggingConfigurationOverrideConfigMap: expr.dig(
            opts,
            ["loggingConfigurationOverrideConfigMap"],
            expr.literal("")
        ),
        podReplicas: expr.dig(opts, ["podReplicas"], 1),
        resources: expr.get(opts, "resources"),
    });
    return {
        apiVersion: "migrations.opensearch.org/v1alpha1",
        kind: "TrafficReplay",
        metadata: {
            name: makeStringTypeProxy(name),
            labels: {
                "workflows.argoproj.io/run-uid": makeStringTypeProxy(expr.getWorkflowValue("uid"))
            }
        },
        spec: makeDirectTypeProxy(expr.mergeDicts(
            workflowSpecFields,
            expr.omit(opts, ...ARGO_REPLAYER_WORKFLOW_OPTION_KEYS)
        )),
    };
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
    options: BaseExpression<Serialized<z.infer<typeof USER_REPLAYER_OPTIONS>>>,
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
    loggingConfigMap: BaseExpression<string>,
    jvmArgs: BaseExpression<string>,

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
            args.jvmArgs);
    return {
        apiVersion: "apps/v1",
        kind: "Deployment",
        metadata: {
            name: makeDirectTypeProxy(args.name),
            ownerReferences: makeOwnerReferences(args.name, args.ownerUid),
            labels: {
                app: "replayer",
                "workflows.argoproj.io/workflow": makeDirectTypeProxy(args.workflowName)
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


export const Replayer = WorkflowBuilder.create({
  k8sResourceName: "replayer",
  serviceAccountName: "argo-workflow-executor",
})

  .addParams(CommonWorkflowParameters)

  .addTemplate("applyTrafficReplay", t => t
      .addRequiredInput("name", typeToken<string>())
      .addRequiredInput("dependsOn", typeToken<string[]>())
      .addRequiredInput("replayerOptions", typeToken<z.infer<typeof ARGO_REPLAYER_OPTIONS>>())
      .addResourceTask(b => b
          .setDefinition({
              action: "apply",
              setOwnerReference: false,
              manifest: makeTrafficReplayManifest(b.inputs.name, b.inputs.dependsOn, b.inputs.replayerOptions)
          }))
      .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
  )

  .addTemplate("applyTrafficReplayWithRetry", t => t
      .addRequiredInput("name", typeToken<string>())
      .addRequiredInput("dependsOn", typeToken<string[]>())
      .addRequiredInput("replayerOptions", typeToken<z.infer<typeof ARGO_REPLAYER_OPTIONS>>())
      .addRequiredInput("retryGateName", typeToken<string>())
      .addOptionalInput("retryGroupName_view", c => "Apply")

      .addSteps(b => b
          .addStep("tryApply", INTERNAL, "applyTrafficReplay", c =>
              c.register({
                  name: b.inputs.name,
                  dependsOn: b.inputs.dependsOn,
                  replayerOptions: b.inputs.replayerOptions,
              }),
              {continueOn: {failed: true}}
          )
          .addStep("waitForFix", ResourceManagement, "waitForApproval", c =>
              c.register({
                  resourceName: b.inputs.retryGateName,
              }),
              {when: c => ({templateExp: expr.equals(c.tryApply.status, "Failed")})}
          )
          .addStep("patchApproval", ResourceManagement, "patchApprovalAnnotation", c =>
              c.register({
                  resourceApiVersion: expr.literal("migrations.opensearch.org/v1alpha1"),
                  resourceKind: expr.literal("TrafficReplay"),
                  resourceName: b.inputs.name,
              }),
              {when: c => ({templateExp: expr.equals(c.waitForFix.status, "Succeeded")})}
          )
          .addStep("resetGate", ResourceManagement, "patchApprovalGatePhase", c =>
              c.register({
                  resourceName: b.inputs.retryGateName,
                  phase: expr.literal("Pending"),
              }),
              {when: c => ({templateExp: expr.equals(c.patchApproval.status, "Succeeded")})}
          )
          .addStepToSelf("retryLoop", c =>
              c.register({
                  name: b.inputs.name,
                  dependsOn: b.inputs.dependsOn,
                  replayerOptions: b.inputs.replayerOptions,
                  retryGateName: b.inputs.retryGateName,
                  retryGroupName_view: b.inputs.retryGroupName_view,
              }),
              {when: c => ({templateExp: expr.equals(c.resetGate.status, "Succeeded")})}
          )
      )
  )

  .addTemplate("createDeployment", (t) =>
    t
      .addRequiredInput("name", typeToken<string>())
      .addRequiredInput("jsonConfig", typeToken<string>())
      .addRequiredInput("kafkaAuthConfigMapName", typeToken<string>())
      .addRequiredInput("kafkaAuthType", typeToken<string>())
      .addRequiredInput("kafkaSecretName", typeToken<string>())
      .addRequiredInput("kafkaCaSecretName", typeToken<string>())
      .addRequiredInput("ownerUid", typeToken<string>())
      .addRequiredInput("podReplicas", typeToken<number>())
      .addRequiredInput("jvmArgs", typeToken<string>())
      .addRequiredInput("loggingConfigurationOverrideConfigMap", typeToken<string>())
      .addRequiredInput("basicAuthSecretName", typeToken<string>())
      .addInputsFromRecord(makeRequiredImageParametersForKeys(["TrafficReplayer"]))
      .addRequiredInput("resources", typeToken<ResourceRequirementsType>())

        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                successCondition: "status.readyReplicas > 0",
                manifest: getReplayerDeploymentManifest({
                    podReplicas: expr.deserializeRecord(b.inputs.podReplicas),
                    useCustomLogging: expr.not(expr.isEmpty(b.inputs.loggingConfigurationOverrideConfigMap)),
                    loggingConfigMap: b.inputs.loggingConfigurationOverrideConfigMap,
                    jvmArgs: b.inputs.jvmArgs,
                    basicAuthSecretName: b.inputs.basicAuthSecretName,
                    name: b.inputs.name,
                    replayerImageName: b.inputs.imageTrafficReplayerLocation,
                    replayerImagePullPolicy: b.inputs.imageTrafficReplayerPullPolicy,
                    workflowName: expr.getWorkflowValue("name"),
                    jsonConfig: expr.toBase64(b.inputs.jsonConfig),
                    resources: expr.deserializeRecord(b.inputs.resources),
                    kafkaAuthConfigMapName: b.inputs.kafkaAuthConfigMapName,
                    kafkaAuthType: b.inputs.kafkaAuthType,
                    kafkaSecretName: b.inputs.kafkaSecretName,
                    kafkaCaSecretName: b.inputs.kafkaCaSecretName,
                    ownerUid: b.inputs.ownerUid,
                })
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
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
      .addRequiredInput(
        "targetConfig",
        typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>(),
      )
      .addRequiredInput(
        "replayerOptions",
        typeToken<z.infer<typeof ARGO_REPLAYER_OPTIONS>>(),
      )
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
            }),
          );
      }),
  )

  .getFullScope();
