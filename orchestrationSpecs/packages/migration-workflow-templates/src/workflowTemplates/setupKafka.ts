import {
    AllowLiteralOrExpression,
    BaseExpression,
    expr,
    INLINE,
    INTERNAL,
    makeDirectTypeProxy,
    Serialized,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {KAFKA_CLUSTER_CREATION_CONFIG, NAMED_KAFKA_CLUSTER_CONFIG} from "@opensearch-migrations/schemas";
import {z} from "zod";
import {K8S_RESOURCE_RETRY_STRATEGY} from "./commonUtils/resourceRetryStrategy";

type KafkaConfig = z.infer<typeof KAFKA_CLUSTER_CREATION_CONFIG>;

function makeDeployKafkaNodePool(args: {
    clusterName: BaseExpression<string>,
    replicas: BaseExpression<number>,
}) {
    return {
        apiVersion: "kafka.strimzi.io/v1",
        kind: "KafkaNodePool",
        metadata: {
            name: "dual-role", // TODO - make this a user setting!
            labels: {"strimzi.io/cluster": args.clusterName}
        },
        spec: {
            replicas: makeDirectTypeProxy(args.replicas),
            roles: ["controller", "broker"],
            storage: {type: "persistent-claim", size: "1Gi", deleteClaim: true}
        }
    };
}

function makeDeployKafkaClusterKraftManifest(args: {
    clusterName: BaseExpression<string>,
    version: BaseExpression<string>,
}) {
    return {
        apiVersion: "kafka.strimzi.io/v1",
        kind: "Kafka",
        metadata: {
            name: args.clusterName,
            annotations: {
                "strimzi.io/node-pools": "enabled",
                "strimzi.io/kraft": "enabled"
            }
        },
        spec: {
            kafka: {
                version: args.version,
                metadataVersion: "4.0-IV3",
                readinessProbe: {initialDelaySeconds: 1, periodSeconds: 2, timeoutSeconds: 2, failureThreshold: 1},
                livenessProbe: {initialDelaySeconds: 1, periodSeconds: 2, timeoutSeconds: 2, failureThreshold: 2},
                listeners: [
                    {name: "plain", port: 9092, type: "internal", tls: false},
                    {name: "tls", port: 9093, type: "internal", tls: true}
                ],
                config: {
                    "auto.create.topics.enable": false,
                    "offsets.topic.replication.factor": 1,
                    "transaction.state.log.replication.factor": 1,
                    "transaction.state.log.min.isr": 1,
                    "default.replication.factor": 1,
                    "min.insync.replicas": 1
                }
            },
            entityOperator: {topicOperator: {}, userOperator: {}}
        }
    };
}

function makeKafkaTopicManifest(args: {
    clusterName: BaseExpression<string>,
    topicName: BaseExpression<string>,
    topicPartitions: BaseExpression<number>,
    topicReplicas: BaseExpression<number>
}) {
    return {
        apiVersion: "kafka.strimzi.io/v1",
        kind: "KafkaTopic",
        metadata: {
            name: args.topicName,
            labels: {"strimzi.io/cluster": args.clusterName}
        },
        spec: {
            partitions: makeDirectTypeProxy(args.topicPartitions),
            replicas: makeDirectTypeProxy(args.topicReplicas),
            config: {"retention.ms": 604800000, "segment.bytes": 1073741824}
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
        .addRequiredInput("clusterConfig", typeToken<KafkaConfig>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeDeployKafkaNodePool({
                    clusterName: b.inputs.clusterName,
                    replicas: expr.dig(expr.deserializeRecord(b.inputs.clusterConfig), ["replicas"], 1),
                })
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("deployKafkaClusterKraft", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("version", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                successCondition: "status.listeners",
                manifest: makeDeployKafkaClusterKraftManifest({
                    clusterName: b.inputs.clusterName,
                    version: b.inputs.version,
                })
            }))
        .addJsonPathOutput("brokers", "{.status.listeners[?(@.name=='plain')].bootstrapServers}",
            typeToken<string>())
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("deployKafkaCluster", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("version", typeToken<string>())
        .addRequiredInput("clusterConfig", typeToken<KafkaConfig>())

        .addSteps(b => b
            .addStep("deployPool", INTERNAL, "deployKafkaNodePool", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    clusterConfig: b.inputs.clusterConfig,
                })
            )
            .addStep("deployCluster", INTERNAL, "deployKafkaClusterKraft", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    version: b.inputs.version,
                })
            )
        )
        .addExpressionOutput("bootstrapServers", c => c.steps.deployCluster.outputs.brokers)
    )


    .addTemplate("createKafkaTopic", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("topicName", typeToken<string>())
        .addRequiredInput("clusterConfig", typeToken<KafkaConfig>())

        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                successCondition: "status.topicName",
                manifest: makeKafkaTopicManifest({
                    clusterName: b.inputs.clusterName,
                    topicName: b.inputs.topicName,
                    topicPartitions: expr.dig(expr.deserializeRecord(b.inputs.clusterConfig), ["partitions"], 1),
                    topicReplicas: expr.dig(expr.deserializeRecord(b.inputs.clusterConfig), ["topicReplicas"], 1),
                })
            }))
        .addJsonPathOutput("topicName", "{.status.topicName}", typeToken<string>())
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
                    clusterConfig: b.inputs.clusterConfig,
                }),
                {continueOn: {failed: true}}
            )
            .addStep("waitForFix", INTERNAL, "suspendForRetry", c =>
                c.register({
                    name: expr.literal("KafkaNodePool")
                }),
                {when: c => ({templateExp: expr.equals(c.tryApply.status, "Failed")})}
            )
            .addStepToSelf("retryLoop", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    clusterConfig: b.inputs.clusterConfig,
                    retryGroupName_view: b.inputs.retryGroupName_view,
                }),
                {when: c => ({templateExp: expr.equals(c.waitForFix.status, "Succeeded")})}
            )
        )
    )

    .addTemplate("deployKafkaClusterKraftWithRetry", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("version", typeToken<string>())
        .addOptionalInput("retryGroupName_view", c => "Apply")

        .addSteps(b => b
            .addStep("tryApply", INTERNAL, "deployKafkaClusterKraft", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    version: b.inputs.version,
                }),
                {continueOn: {failed: true}}
            )
            .addStep("waitForFix", INTERNAL, "suspendForRetry", c =>
                c.register({
                    name: expr.literal("KafkaCluster")
                }),
                {when: c => ({templateExp: expr.equals(c.tryApply.status, "Failed")})}
            )
            .addStepToSelf("retryLoop", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    version: b.inputs.version,
                    retryGroupName_view: b.inputs.retryGroupName_view,
                }),
                {when: c => ({templateExp: expr.equals(c.waitForFix.status, "Succeeded")})}
            )
        )
        .addExpressionOutput("brokers", c => c.steps.tryApply.outputs.brokers)
    )

    .addTemplate("createKafkaTopicWithRetry", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("topicName", typeToken<string>())
        .addRequiredInput("clusterConfig", typeToken<KafkaConfig>())
        .addOptionalInput("retryGroupName_view", c => "Apply")

        .addSteps(b => b
            .addStep("tryApply", INTERNAL, "createKafkaTopic", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    topicName: b.inputs.topicName,
                    clusterConfig: b.inputs.clusterConfig,
                }),
                {continueOn: {failed: true}}
            )
            .addStep("waitForFix", INTERNAL, "suspendForRetry", c =>
                c.register({
                    name: expr.literal("KafkaTopic")
                }),
                {when: c => ({templateExp: expr.equals(c.tryApply.status, "Failed")})}
            )
            .addStepToSelf("retryLoop", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    topicName: b.inputs.topicName,
                    clusterConfig: b.inputs.clusterConfig,
                    retryGroupName_view: b.inputs.retryGroupName_view,
                }),
                {when: c => ({templateExp: expr.equals(c.waitForFix.status, "Succeeded")})}
            )
        )
        .addExpressionOutput("topicName", c => c.steps.tryApply.outputs.topicName)
    )

    // Combined retry template for full Kafka cluster deployment
    .addTemplate("deployKafkaClusterWithRetry", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("version", typeToken<string>())
        .addRequiredInput("clusterConfig", typeToken<KafkaConfig>())

        .addSteps(b => b
            .addStep("deployPool", INTERNAL, "deployKafkaNodePoolWithRetry", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    clusterConfig: b.inputs.clusterConfig,
                    retryGroupName_view: expr.concat(expr.literal("KafkaNodePool: "), b.inputs.clusterName),
                })
            )
            .addStep("deployCluster", INTERNAL, "deployKafkaClusterKraftWithRetry", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    version: b.inputs.version,
                    retryGroupName_view: expr.concat(expr.literal("KafkaCluster: "), b.inputs.clusterName),
                })
            )
        )
        .addExpressionOutput("bootstrapServers", c => c.steps.deployCluster.outputs.brokers)
    )


    .getFullScope();
