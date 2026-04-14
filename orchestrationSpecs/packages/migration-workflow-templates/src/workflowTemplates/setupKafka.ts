import {
    BaseExpression,
    expr,
    INTERNAL,
    makeDirectTypeProxy,
    makeStringTypeProxy,
    Serialized,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {KAFKA_CLUSTER_CREATION_CONFIG} from "@opensearch-migrations/schemas";
import {z} from "zod";
import {K8S_RESOURCE_RETRY_STRATEGY} from "./commonUtils/resourceRetryStrategy";
import {ResourceManagement} from "./resourceManagement";

type KafkaConfig = z.infer<typeof KAFKA_CLUSTER_CREATION_CONFIG>;

function getKafkaAuthType(config: BaseExpression<Serialized<KafkaConfig>>) {
    return expr.dig(expr.deserializeRecord(config), ["auth", "type"], "none");
}

function makePlainListener() {
    return expr.makeDict({
        name: "plain",
        port: 9092,
        type: "internal",
        tls: false
    });
}

function makeTlsListener() {
    return expr.makeDict({
        name: "tls",
        port: 9093,
        type: "internal",
        tls: true
    });
}

function makeScramListener() {
    return expr.makeDict({
        name: "tls",
        port: 9093,
        type: "internal",
        tls: true,
        authentication: expr.makeDict({type: "scram-sha-512"})
    });
}

function makeManagedKafkaListeners(authType: BaseExpression<string>) {
    return expr.ternary(
        expr.equals(authType, expr.literal("scram-sha-512")),
        expr.toArray(makeScramListener()),
        expr.toArray(makePlainListener(), makeTlsListener())
    );
}

function makeManagedKafkaUserManifest(args: {
    clusterName: BaseExpression<string>,
    userSpec: BaseExpression<Serialized<Record<string, any>>>,
    workflowUid: BaseExpression<string>,
}): Record<string, any> {
    return {
        apiVersion: "kafka.strimzi.io/v1",
        kind: "KafkaUser",
        metadata: {
            name: expr.concat(args.clusterName, expr.literal("-migration-app")),
            labels: {
                "strimzi.io/cluster": args.clusterName,
                "workflows.argoproj.io/run-uid": makeStringTypeProxy(args.workflowUid)
            }
        },
        spec: makeDirectTypeProxy(args.userSpec as any) as any
    };
}

function makeDeployKafkaNodePool(args: {
    clusterName: BaseExpression<string>,
    nodePoolSpec: BaseExpression<Serialized<Record<string, any>>>,
    workflowUid: BaseExpression<string>,
}): Record<string, any> {
    return {
        apiVersion: "kafka.strimzi.io/v1",
        kind: "KafkaNodePool",
        metadata: {
            name: "dual-role", // TODO - make this a user setting!
            labels: {
                "strimzi.io/cluster": args.clusterName,
                "workflows.argoproj.io/run-uid": makeStringTypeProxy(args.workflowUid)
            }
        },
        spec: makeDirectTypeProxy(args.nodePoolSpec as any) as any
    };
}

function makeManagedKafkaSpecNoAuth(args: {
    version: BaseExpression<string>,
    clusterConfig: BaseExpression<Serialized<KafkaConfig>>,
}) {
    const config = expr.deserializeRecord(args.clusterConfig);
    const kafkaSpecOverrides = expr.dig(config, ["clusterSpecOverrides", "kafka"], expr.makeDict({}));

    return expr.mergeDicts(
        expr.makeDict({
            version: args.version,
            metadataVersion: "4.0-IV3",
            listeners: expr.toArray(makePlainListener(), makeTlsListener())
        }),
        kafkaSpecOverrides
    );
}

function makeManagedKafkaSpecScram(args: {
    version: BaseExpression<string>,
    clusterConfig: BaseExpression<Serialized<KafkaConfig>>,
}) {
    const config = expr.deserializeRecord(args.clusterConfig);
    const kafkaSpecOverrides = expr.dig(config, ["clusterSpecOverrides", "kafka"], expr.makeDict({}));

    return expr.mergeDicts(
        expr.makeDict({
            version: args.version,
            metadataVersion: "4.0-IV3",
            listeners: expr.toArray(makeScramListener())
        }),
        kafkaSpecOverrides
    );
}

function makeManagedKafkaUserSpec(clusterConfig: BaseExpression<Serialized<KafkaConfig>>) {
    return expr.makeDict({
        authentication: expr.makeDict({
            type: expr.dig(expr.deserializeRecord(clusterConfig), ["auth", "type"], "none")
        })
    });
}

function makeDeployKafkaClusterKraftManifest(args: {
    clusterName: BaseExpression<string>,
    kafkaSpec: BaseExpression<Serialized<Record<string, any>>>,
    workflowUid: BaseExpression<string>,
}): Record<string, any> {
    return {
        apiVersion: "kafka.strimzi.io/v1",
        kind: "Kafka",
        metadata: {
            name: args.clusterName,
            labels: {
                "workflows.argoproj.io/run-uid": makeStringTypeProxy(args.workflowUid)
            },
            annotations: {
                "strimzi.io/node-pools": "enabled",
                "strimzi.io/kraft": "enabled"
            }
        },
        spec: {
            kafka: makeDirectTypeProxy(args.kafkaSpec as any) as any,
            entityOperator: {topicOperator: {}, userOperator: {}}
        }
    };
}

function shouldCreateManagedKafkaUser(clusterConfig: BaseExpression<Serialized<KafkaConfig>>) {
    return expr.equals(getKafkaAuthType(clusterConfig), expr.literal("scram-sha-512"));
}

function makeKafkaTopicManifest(args: {
    clusterName: BaseExpression<string>,
    topicName: BaseExpression<string>,
    workflowUid: BaseExpression<string>,
    partitions: BaseExpression<Serialized<number>>,
    replicas: BaseExpression<Serialized<number>>,
    topicConfig: BaseExpression<Serialized<Record<string, any>>>,
}): Record<string, any> {
    return {
        apiVersion: "kafka.strimzi.io/v1",
        kind: "KafkaTopic",
        metadata: {
            name: args.topicName,
            labels: {
                "strimzi.io/cluster": args.clusterName,
                "workflows.argoproj.io/run-uid": makeStringTypeProxy(args.workflowUid)
            }
        },
        spec: {
            partitions: makeDirectTypeProxy(args.partitions),
            replicas: makeDirectTypeProxy(args.replicas),
            config: makeDirectTypeProxy(args.topicConfig as any) as any,
        }
    };
}


export const SetupKafka = WorkflowBuilder.create({
    k8sResourceName: "setup-kafka",
    serviceAccountName: "argo-workflow-executor",
    k8sMetadata: {},
    parallelism: 1
})
    .addParams(CommonWorkflowParameters)


    // Leaf templates defined first so deployKafkaCluster can reference them via INTERNAL

    .addTemplate("deployKafkaNodePool", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("nodePoolSpec", typeToken<Serialized<Record<string, any>>>())
        .addRequiredInput("workflowUid", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeDeployKafkaNodePool({
                    clusterName: b.inputs.clusterName,
                    nodePoolSpec: b.inputs.nodePoolSpec,
                    workflowUid: b.inputs.workflowUid,
                })
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("deployKafkaClusterKraftNoAuth", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("kafkaSpec", typeToken<Serialized<Record<string, any>>>())
        .addRequiredInput("workflowUid", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                successCondition: "status.listeners",
                manifest: makeDeployKafkaClusterKraftManifest({
                    clusterName: b.inputs.clusterName,
                    kafkaSpec: b.inputs.kafkaSpec,
                    workflowUid: b.inputs.workflowUid,
                })
            }))
        .addJsonPathOutput("brokers", "{.status.listeners[?(@.name=='plain')].bootstrapServers}",
            typeToken<string>())
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("deployKafkaClusterKraftScram", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("kafkaSpec", typeToken<Serialized<Record<string, any>>>())
        .addRequiredInput("workflowUid", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                successCondition: "status.listeners",
                manifest: makeDeployKafkaClusterKraftManifest({
                    clusterName: b.inputs.clusterName,
                    kafkaSpec: b.inputs.kafkaSpec,
                    workflowUid: b.inputs.workflowUid,
                })
            }))
        .addJsonPathOutput("brokers", "{.status.listeners[?(@.name=='tls')].bootstrapServers}",
            typeToken<string>())
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("deployKafkaCluster", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("version", typeToken<string>())
        .addRequiredInput("clusterConfig", typeToken<KafkaConfig>())
        .addRequiredInput("workflowUid", typeToken<string>())

        .addSteps(b => b
            .addStep("deployNoAuthCluster", INTERNAL, "deployKafkaClusterKraftNoAuth", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    kafkaSpec: expr.recordToString(makeManagedKafkaSpecNoAuth({
                        version: b.inputs.version,
                        clusterConfig: b.inputs.clusterConfig,
                    })),
                    workflowUid: b.inputs.workflowUid,
                }),
                {when: c => ({templateExp: expr.not(shouldCreateManagedKafkaUser(b.inputs.clusterConfig))})}
            )
            .addStep("deployScramCluster", INTERNAL, "deployKafkaClusterKraftScram", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    kafkaSpec: expr.recordToString(makeManagedKafkaSpecScram({
                        version: b.inputs.version,
                        clusterConfig: b.inputs.clusterConfig,
                    })),
                    workflowUid: b.inputs.workflowUid,
                }),
                {when: c => ({templateExp: shouldCreateManagedKafkaUser(b.inputs.clusterConfig)})}
            )
        )
        .addExpressionOutput("bootstrapServers", c => expr.ternary(
            shouldCreateManagedKafkaUser(c.inputs.clusterConfig),
            c.steps.deployScramCluster.outputs.brokers,
            c.steps.deployNoAuthCluster.outputs.brokers
        ))
    )


    .addTemplate("createKafkaTopic", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("topicName", typeToken<string>())
        .addRequiredInput("workflowUid", typeToken<string>())
        .addRequiredInput("partitions", typeToken<number>())
        .addRequiredInput("replicas", typeToken<number>())
        .addRequiredInput("topicConfig", typeToken<Serialized<Record<string, any>>>())

        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                successCondition: "status.topicName",
                manifest: makeKafkaTopicManifest({
                    clusterName: b.inputs.clusterName,
                    topicName: b.inputs.topicName,
                    workflowUid: b.inputs.workflowUid,
                    partitions: b.inputs.partitions,
                    replicas: b.inputs.replicas,
                    topicConfig: b.inputs.topicConfig,
                })
            }))
        .addJsonPathOutput("topicName", "{.status.topicName}", typeToken<string>())
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    .addTemplate("createKafkaUser", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("userSpec", typeToken<Serialized<Record<string, any>>>())
        .addRequiredInput("workflowUid", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                successCondition: "status.conditions",
                manifest: makeManagedKafkaUserManifest({
                    clusterName: b.inputs.clusterName,
                    userSpec: b.inputs.userSpec,
                    workflowUid: b.inputs.workflowUid,
                })
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    // ── Suspend template for VAP retry loops ─────────────────────────────
    .addTemplate("suspendForRetry", t => t
        .addRequiredInput("name", typeToken<string>())
        .addSuspend()
    )


    // ── Reconciliation templates with VAP-aware recursive retry ──────────
    // These templates handle VAP rejections by:
    //   1. Attempting the apply (with continueOn.failed)
    //   2. If failed, suspend for user to fix issues
    //   3. After resume, recursively call self until success
    // If VAP blocked the change, user must either:
    //   - Fix their config to match deployed state, OR
    //   - Add approval annotation (e.g., approved-replicas=<value>)
    // Then click Resume in Argo UI.

    .addTemplate("deployKafkaNodePoolWithRetry", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("clusterConfig", typeToken<KafkaConfig>())
        .addOptionalInput("retryGroupName_view", c => "Apply")

        .addSteps(b => b
            .addStep("tryApply", INTERNAL, "deployKafkaNodePool", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    nodePoolSpec: expr.recordToString(expr.dig(
                        expr.deserializeRecord(b.inputs.clusterConfig),
                        ["nodePoolSpecOverrides"],
                        expr.makeDict({})
                    )),
                    workflowUid: expr.getWorkflowValue("uid"),
                }),
                {continueOn: {failed: true}}
            )
            .addStep("waitForFix", INTERNAL, "suspendForRetry", c =>
                c.register({
                    name: expr.literal("KafkaNodePool")
                }),
                {when: c => ({templateExp: expr.equals(c.tryApply.status, "Failed")})}
            )
            .addStep("patchApproval", ResourceManagement, "patchApprovalAnnotation", c =>
                c.register({
                    resourceApiVersion: expr.literal("kafka.strimzi.io/v1"),
                    resourceKind: expr.literal("KafkaNodePool"),
                    resourceName: expr.literal("dual-role"),
                }),
                {when: c => ({templateExp: expr.equals(c.waitForFix.status, "Succeeded")})}
            )
            .addStepToSelf("retryLoop", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    clusterConfig: b.inputs.clusterConfig,
                    retryGroupName_view: b.inputs.retryGroupName_view,
                }),
                {when: c => ({templateExp: expr.equals(c.patchApproval.status, "Succeeded")})}
            )
        )
    )

    .addTemplate("deployKafkaClusterKraftWithRetry", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("version", typeToken<string>())
        .addRequiredInput("clusterConfig", typeToken<KafkaConfig>())
        .addOptionalInput("retryGroupName_view", c => "Apply")

        .addSteps(b => b
            .addStep("tryApply", INTERNAL, "deployKafkaCluster", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    version: b.inputs.version,
                    clusterConfig: b.inputs.clusterConfig,
                    workflowUid: expr.getWorkflowValue("uid"),
                }),
                {continueOn: {failed: true}}
            )
            .addStep("waitForFix", INTERNAL, "suspendForRetry", c =>
                c.register({
                    name: expr.literal("KafkaCluster")
                }),
                {when: c => ({templateExp: expr.equals(c.tryApply.status, "Failed")})}
            )
            .addStep("patchApproval", ResourceManagement, "patchApprovalAnnotation", c =>
                c.register({
                    resourceApiVersion: expr.literal("kafka.strimzi.io/v1"),
                    resourceKind: expr.literal("Kafka"),
                    resourceName: b.inputs.clusterName,
                }),
                {when: c => ({templateExp: expr.equals(c.waitForFix.status, "Succeeded")})}
            )
            .addStepToSelf("retryLoop", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    version: b.inputs.version,
                    clusterConfig: b.inputs.clusterConfig,
                    retryGroupName_view: b.inputs.retryGroupName_view,
                }),
                {when: c => ({templateExp: expr.equals(c.patchApproval.status, "Succeeded")})}
            )
        )
        .addExpressionOutput("brokers", c => c.steps.tryApply.outputs.bootstrapServers)
    )

    .addTemplate("createKafkaTopicWithRetry", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("topicName", typeToken<string>())
        .addRequiredInput("partitions", typeToken<number>())
        .addRequiredInput("replicas", typeToken<number>())
        .addRequiredInput("topicConfig", typeToken<Serialized<Record<string, any>>>())
        .addOptionalInput("retryGroupName_view", c => "Apply")

        .addSteps(b => b
            .addStep("tryApply", INTERNAL, "createKafkaTopic", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    topicName: b.inputs.topicName,
                    workflowUid: expr.getWorkflowValue("uid"),
                    partitions: b.inputs.partitions,
                    replicas: b.inputs.replicas,
                    topicConfig: b.inputs.topicConfig,
                }),
                {continueOn: {failed: true}}
            )
            .addStep("waitForFix", INTERNAL, "suspendForRetry", c =>
                c.register({
                    name: expr.literal("KafkaTopic")
                }),
                {when: c => ({templateExp: expr.equals(c.tryApply.status, "Failed")})}
            )
            .addStep("patchApproval", ResourceManagement, "patchApprovalAnnotation", c =>
                c.register({
                    resourceApiVersion: expr.literal("kafka.strimzi.io/v1"),
                    resourceKind: expr.literal("KafkaTopic"),
                    resourceName: b.inputs.topicName,
                }),
                {when: c => ({templateExp: expr.equals(c.waitForFix.status, "Succeeded")})}
            )
            .addStepToSelf("retryLoop", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    topicName: b.inputs.topicName,
                    partitions: b.inputs.partitions,
                    replicas: b.inputs.replicas,
                    topicConfig: b.inputs.topicConfig,
                    retryGroupName_view: b.inputs.retryGroupName_view,
                }),
                {when: c => ({templateExp: expr.equals(c.patchApproval.status, "Succeeded")})}
            )
        )
        .addExpressionOutput("topicName", c => c.steps.tryApply.outputs.topicName)
    )

    .addTemplate("createKafkaUserWithRetry", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("clusterConfig", typeToken<KafkaConfig>())
        .addOptionalInput("retryGroupName_view", c => "Apply")

        .addSteps(b => b
            .addStep("tryApply", INTERNAL, "createKafkaUser", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    userSpec: expr.recordToString(makeManagedKafkaUserSpec(b.inputs.clusterConfig)),
                    workflowUid: expr.getWorkflowValue("uid"),
                }),
                {continueOn: {failed: true}}
            )
            .addStep("waitForFix", INTERNAL, "suspendForRetry", c =>
                c.register({
                    name: expr.literal("KafkaUser")
                }),
                {when: c => ({templateExp: expr.equals(c.tryApply.status, "Failed")})}
            )
            .addStep("patchApproval", ResourceManagement, "patchApprovalAnnotation", c =>
                c.register({
                    resourceApiVersion: expr.literal("kafka.strimzi.io/v1"),
                    resourceKind: expr.literal("KafkaUser"),
                    resourceName: expr.concat(b.inputs.clusterName, expr.literal("-migration-app")),
                }),
                {when: c => ({templateExp: expr.equals(c.waitForFix.status, "Succeeded")})}
            )
            .addStepToSelf("retryLoop", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    clusterConfig: b.inputs.clusterConfig,
                    retryGroupName_view: b.inputs.retryGroupName_view,
                }),
                {when: c => ({templateExp: expr.equals(c.patchApproval.status, "Succeeded")})}
            )
        )
    )

    // Combined retry template for full Kafka cluster deployment
    .addTemplate("deployKafkaClusterWithRetry", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("version", typeToken<string>())
        .addRequiredInput("clusterConfig", typeToken<KafkaConfig>())

        .addSteps(b => b
            .addStep("checkExisting", ResourceManagement, "readKafkaConnectionProfile", c =>
                c.register({
                    resourceName: b.inputs.clusterName,
                }),
                {continueOn: {failed: true}}
            )
            .addStep("deployPool", INTERNAL, "deployKafkaNodePoolWithRetry", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    clusterConfig: b.inputs.clusterConfig,
                    retryGroupName_view: expr.concat(expr.literal("KafkaNodePool: "), b.inputs.clusterName),
                }),
                {when: c => ({templateExp: expr.not(expr.equals(c.checkExisting.status, "Succeeded"))})}
            )
            .addStep("deployCluster", INTERNAL, "deployKafkaClusterKraftWithRetry", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    version: b.inputs.version,
                    clusterConfig: b.inputs.clusterConfig,
                    retryGroupName_view: expr.concat(expr.literal("KafkaCluster: "), b.inputs.clusterName),
                }),
                {when: c => ({templateExp: expr.not(expr.equals(c.checkExisting.status, "Succeeded"))})}
            )
            .addStep("createKafkaUser", INTERNAL, "createKafkaUserWithRetry", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    clusterConfig: b.inputs.clusterConfig,
                    retryGroupName_view: expr.concat(expr.literal("KafkaUser: "), b.inputs.clusterName),
                }),
                {when: c => ({templateExp: expr.and(
                    expr.not(expr.equals(c.checkExisting.status, "Succeeded")),
                    shouldCreateManagedKafkaUser(b.inputs.clusterConfig)
                )})}
            )
        )
        .addExpressionOutput("bootstrapServers", c => expr.ternary(
            expr.equals(c.steps.checkExisting.status, "Succeeded"),
            c.steps.checkExisting.outputs.bootstrapServers,
            c.steps.deployCluster.outputs.brokers
        ))
    )


    .getFullScope();
