import {
    BaseExpression,
    expr,
    INTERNAL,
    makeParameterLoop,
    makeDirectTypeProxy,
    makeStringTypeProxy,
    Serialized,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {OwnerReference} from "@opensearch-migrations/k8s-types";
import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {KAFKA_CLUSTER_CREATION_CONFIG, NAMED_KAFKA_CLUSTER_CONFIG} from "@opensearch-migrations/schemas";
import {z} from "zod";
import {K8S_RESOURCE_RETRY_STRATEGY} from "./commonUtils/resourceRetryStrategy";
import {ResourceManagement} from "./resourceManagement";

type KafkaConfig = z.infer<typeof KAFKA_CLUSTER_CREATION_CONFIG>;

function makeOwnerReferences(
    ownerName: BaseExpression<string>,
    ownerUid: BaseExpression<string>,
): OwnerReference[] {
    return [{
        apiVersion: "migrations.opensearch.org/v1alpha1",
        kind: "KafkaCluster",
        name: makeDirectTypeProxy(ownerName),
        uid: makeDirectTypeProxy(ownerUid),
        controller: true,
        blockOwnerDeletion: true,
    }];
}

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
    ownerUid: BaseExpression<string>,
}): Record<string, any> {
    return {
        apiVersion: "kafka.strimzi.io/v1",
        kind: "KafkaUser",
        metadata: {
            name: expr.concat(args.clusterName, expr.literal("-migration-app")),
            ownerReferences: makeOwnerReferences(args.clusterName, args.ownerUid),
            labels: {
                "strimzi.io/cluster": args.clusterName,
                "workflows.argoproj.io/run-uid": makeStringTypeProxy(args.workflowUid)
            }
        },
        spec: makeDirectTypeProxy(expr.deserializeRecord(args.userSpec))
    };
}

function makeDeployKafkaNodePool(args: {
    clusterName: BaseExpression<string>,
    nodePoolSpec: BaseExpression<Serialized<Record<string, any>>>,
    workflowUid: BaseExpression<string>,
    ownerUid: BaseExpression<string>,
}): Record<string, any> {
    return {
        apiVersion: "kafka.strimzi.io/v1",
        kind: "KafkaNodePool",
        metadata: {
            name: "dual-role", // TODO - make this a user setting!
            ownerReferences: makeOwnerReferences(args.clusterName, args.ownerUid),
            labels: {
                "strimzi.io/cluster": args.clusterName,
                "workflows.argoproj.io/run-uid": makeStringTypeProxy(args.workflowUid)
            }
        },
        spec: makeDirectTypeProxy(expr.deserializeRecord(args.nodePoolSpec))
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
    ownerUid: BaseExpression<string>,
}): Record<string, any> {
    return {
        apiVersion: "kafka.strimzi.io/v1",
        kind: "Kafka",
        metadata: {
            name: args.clusterName,
            ownerReferences: makeOwnerReferences(args.clusterName, args.ownerUid),
            labels: {
                "workflows.argoproj.io/run-uid": makeStringTypeProxy(args.workflowUid)
            },
            annotations: {
                "strimzi.io/node-pools": "enabled",
                "strimzi.io/kraft": "enabled"
            }
        },
        spec: {
            kafka: makeDirectTypeProxy(expr.deserializeRecord(args.kafkaSpec)),
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
    ownerUid: BaseExpression<string>,
    partitions: BaseExpression<Serialized<number>>,
    replicas: BaseExpression<Serialized<number>>,
    topicConfig: BaseExpression<Serialized<Record<string, any>>>,
}): Record<string, any> {
    return {
        apiVersion: "kafka.strimzi.io/v1",
        kind: "KafkaTopic",
        metadata: {
            name: args.topicName,
            ownerReferences: makeOwnerReferences(args.clusterName, args.ownerUid),
            labels: {
                "strimzi.io/cluster": args.clusterName,
                "workflows.argoproj.io/run-uid": makeStringTypeProxy(args.workflowUid)
            }
        },
        spec: {
            partitions: makeDirectTypeProxy(args.partitions),
            replicas: makeDirectTypeProxy(args.replicas),
            config: makeDirectTypeProxy(expr.deserializeRecord(args.topicConfig)),
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
        .addRequiredInput("ownerUid", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeDeployKafkaNodePool({
                    clusterName: b.inputs.clusterName,
                    nodePoolSpec: b.inputs.nodePoolSpec,
                    workflowUid: b.inputs.workflowUid,
                    ownerUid: b.inputs.ownerUid,
                })
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("deployKafkaClusterKraftNoAuth", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("kafkaSpec", typeToken<Serialized<Record<string, any>>>())
        .addRequiredInput("workflowUid", typeToken<string>())
        .addRequiredInput("ownerUid", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                successCondition: "status.listeners",
                manifest: makeDeployKafkaClusterKraftManifest({
                    clusterName: b.inputs.clusterName,
                    kafkaSpec: b.inputs.kafkaSpec,
                    workflowUid: b.inputs.workflowUid,
                    ownerUid: b.inputs.ownerUid,
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
        .addRequiredInput("ownerUid", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                successCondition: "status.listeners",
                manifest: makeDeployKafkaClusterKraftManifest({
                    clusterName: b.inputs.clusterName,
                    kafkaSpec: b.inputs.kafkaSpec,
                    workflowUid: b.inputs.workflowUid,
                    ownerUid: b.inputs.ownerUid,
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
        .addRequiredInput("ownerUid", typeToken<string>())

        .addSteps(b => b
            .addStep("deployNoAuthCluster", INTERNAL, "deployKafkaClusterKraftNoAuth", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    kafkaSpec: expr.recordToString(makeManagedKafkaSpecNoAuth({
                        version: b.inputs.version,
                        clusterConfig: b.inputs.clusterConfig,
                    })),
                    workflowUid: b.inputs.workflowUid,
                    ownerUid: b.inputs.ownerUid,
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
                    ownerUid: b.inputs.ownerUid,
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
        .addRequiredInput("ownerUid", typeToken<string>())
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
                    ownerUid: b.inputs.ownerUid,
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
        .addRequiredInput("ownerUid", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                successCondition: "status.conditions",
                manifest: makeManagedKafkaUserManifest({
                    clusterName: b.inputs.clusterName,
                    userSpec: b.inputs.userSpec,
                    workflowUid: b.inputs.workflowUid,
                    ownerUid: b.inputs.ownerUid,
                })
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    .addTemplate("deployKafkaClusterAndTopics", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("version", typeToken<string>())
        .addRequiredInput("clusterConfig", typeToken<KafkaConfig>())
        .addRequiredInput("ownerUid", typeToken<string>())

        .addSteps(b => {
            return b
                .addStep("deployNodePool", INTERNAL, "deployKafkaNodePool", c =>
                    c.register({
                        clusterName: b.inputs.clusterName,
                        nodePoolSpec: expr.recordToString(expr.dig(
                            expr.deserializeRecord(b.inputs.clusterConfig),
                            ["nodePoolSpecOverrides"],
                            expr.makeDict({})
                        )),
                        workflowUid: expr.getWorkflowValue("uid"),
                        ownerUid: b.inputs.ownerUid,
                    })
                )
                .addStep("deployCluster", INTERNAL, "deployKafkaCluster", c =>
                    c.register({
                        clusterName: b.inputs.clusterName,
                        version: b.inputs.version,
                        clusterConfig: b.inputs.clusterConfig,
                        workflowUid: expr.getWorkflowValue("uid"),
                        ownerUid: b.inputs.ownerUid,
                    })
                )
                .addStep("deployKafkaUser", INTERNAL, "createKafkaUser", c =>
                    c.register({
                        clusterName: b.inputs.clusterName,
                        userSpec: expr.recordToString(makeManagedKafkaUserSpec(b.inputs.clusterConfig)),
                        workflowUid: expr.getWorkflowValue("uid"),
                        ownerUid: b.inputs.ownerUid,
                    }),
                    {when: c => ({templateExp: shouldCreateManagedKafkaUser(b.inputs.clusterConfig)})}
                );
        })
    )


    .getFullScope();
