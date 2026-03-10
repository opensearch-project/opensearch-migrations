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
            labels: { "strimzi.io/cluster": args.clusterName }
        },
        spec: {
            replicas: makeDirectTypeProxy(args.replicas),
            roles: ["controller", "broker"],
            storage: { type: "ephemeral" }
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
                readinessProbe: { initialDelaySeconds: 1, periodSeconds: 2, timeoutSeconds: 2, failureThreshold: 1 },
                livenessProbe:  { initialDelaySeconds: 1, periodSeconds: 2, timeoutSeconds: 2, failureThreshold: 2 },
                listeners: [
                    { name: "plain", port: 9092, type: "internal", tls: false },
                    { name: "tls",   port: 9093, type: "internal", tls: true  }
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
            entityOperator: { topicOperator: {}, userOperator: {} }
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
            labels: { "strimzi.io/cluster": args.clusterName }
        },
        spec: {
            partitions: makeDirectTypeProxy(args.topicPartitions),
            replicas:   makeDirectTypeProxy(args.topicReplicas),
            config: { "retention.ms": 604800000, "segment.bytes": 1073741824 }
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
        .addRequiredInput("clusterName",   typeToken<string>())
        .addRequiredInput("clusterConfig", typeToken<KafkaConfig>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: true,
                manifest: makeDeployKafkaNodePool({
                    clusterName: b.inputs.clusterName,
                    replicas:    expr.dig(expr.deserializeRecord(b.inputs.clusterConfig), ["replicas"], 1),
                })
            }))
    )


    .addTemplate("deployKafkaClusterKraft", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("version",     typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: true,
                successCondition: "status.listeners",
                manifest: makeDeployKafkaClusterKraftManifest({
                    clusterName: b.inputs.clusterName,
                    version:     b.inputs.version,
                })
            }))
        .addJsonPathOutput("brokers", "{.status.listeners[?(@.name=='plain')].bootstrapServers}",
            typeToken<string>())
    )


    .addTemplate("deployKafkaCluster", t => t
        .addRequiredInput("clusterName",   typeToken<string>())
        .addRequiredInput("version",       typeToken<string>())
        .addRequiredInput("clusterConfig", typeToken<KafkaConfig>())

        .addSteps(b => b
            .addStep("deployPool", INTERNAL, "deployKafkaNodePool", c =>
                c.register({
                    clusterName:   b.inputs.clusterName,
                    clusterConfig: b.inputs.clusterConfig,
                })
            )
            .addStep("deployCluster", INTERNAL, "deployKafkaClusterKraft", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    version:     b.inputs.version,
                })
            )
        )
        .addExpressionOutput("bootstrapServers", c => c.steps.deployCluster.outputs.brokers)
    )


    .addTemplate("createKafkaTopic", t => t
        .addRequiredInput("clusterName",   typeToken<string>())
        .addRequiredInput("topicName",     typeToken<string>())
        .addRequiredInput("clusterConfig", typeToken<KafkaConfig>())

        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: true,
                successCondition: "status.topicName",
                manifest: makeKafkaTopicManifest({
                    clusterName:     b.inputs.clusterName,
                    topicName:       b.inputs.topicName,
                    topicPartitions: expr.dig(expr.deserializeRecord(b.inputs.clusterConfig), ["partitions"],    1),
                    topicReplicas:   expr.dig(expr.deserializeRecord(b.inputs.clusterConfig), ["topicReplicas"], 1),
                })
            }))
        .addJsonPathOutput("topicName", "{.status.topicName}", typeToken<string>())
    )


    // ── Reconciliation templates with VAP-aware suspend/retry ─────────────
    // These templates handle VAP rejections by:
    //   1. Attempting the apply (with continueOn.failed)
    //   2. Always suspending for user to verify or fix issues
    //   3. Retrying after the user clicks Resume
    // If VAP blocked the change, user must either:
    //   - Fix their config to match deployed state, OR
    //   - Add approval annotation (e.g., approved-replicas=<value>)
    // Then click Resume in Argo UI.

    .addTemplate("deployKafkaNodePoolWithRetry", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("clusterConfig", typeToken<KafkaConfig>())

        .addSteps(b => b
            // Step 1: Try apply, continue on failure (VAP rejection)
            .addStep("tryApply", INTERNAL, "deployKafkaNodePool", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    clusterConfig: b.inputs.clusterConfig,
                }),
                { continueOn: { failed: true } }
            )
            // Step 2: Suspend for user to verify success or fix issues
            .addStep("waitForUserAction", INLINE, tb => tb.addSuspend())
            // Step 3: After resume, retry the apply (idempotent if already succeeded)
            .addStep("retryApply", INTERNAL, "deployKafkaNodePool", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    clusterConfig: b.inputs.clusterConfig,
                })
            )
        )
    )

    .addTemplate("deployKafkaClusterKraftWithRetry", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("version", typeToken<string>())

        .addSteps(b => b
            .addStep("tryApply", INTERNAL, "deployKafkaClusterKraft", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    version: b.inputs.version,
                }),
                { continueOn: { failed: true } }
            )
            .addStep("waitForUserAction", INLINE, tb => tb.addSuspend())
            .addStep("retryApply", INTERNAL, "deployKafkaClusterKraft", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    version: b.inputs.version,
                })
            )
        )
        .addExpressionOutput("brokers", c => c.steps.retryApply.outputs.brokers)
    )

    .addTemplate("createKafkaTopicWithRetry", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("topicName", typeToken<string>())
        .addRequiredInput("clusterConfig", typeToken<KafkaConfig>())

        .addSteps(b => b
            .addStep("tryApply", INTERNAL, "createKafkaTopic", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    topicName: b.inputs.topicName,
                    clusterConfig: b.inputs.clusterConfig,
                }),
                { continueOn: { failed: true } }
            )
            .addStep("waitForUserAction", INLINE, tb => tb.addSuspend())
            .addStep("retryApply", INTERNAL, "createKafkaTopic", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    topicName: b.inputs.topicName,
                    clusterConfig: b.inputs.clusterConfig,
                })
            )
        )
        .addExpressionOutput("topicName", c => c.steps.retryApply.outputs.topicName)
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
                })
            )
            .addStep("deployCluster", INTERNAL, "deployKafkaClusterKraftWithRetry", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    version: b.inputs.version,
                })
            )
        )
        .addExpressionOutput("bootstrapServers", c => c.steps.deployCluster.outputs.brokers)
    )


    .getFullScope();
