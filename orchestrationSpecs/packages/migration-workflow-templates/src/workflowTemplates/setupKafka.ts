import {
    BaseExpression,
    expr,
    INTERNAL,
    selectInputsForRegister, Serialized,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";


import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";


function makeDeployKafkaClusterZookeeperManifest(clusterName: BaseExpression<string>) {
    return {
        apiVersion: "kafka.strimzi.io/v1beta2",
        kind: "Kafka",
        metadata: {
            name: clusterName
        },
        spec: {
            kafka: {
                version: "3.9.0",
                replicas: 1,
                listeners: [
                    {
                        name: "tls",
                        port: 9093,
                        type: "internal",
                        tls: true
                    }
                ],
                config: {
                    "offsets.topic.replication.factor": 1,
                    "transaction.state.log.replication.factor": 1,
                    "transaction.state.log.min.isr": 1,
                    "default.replication.factor": 1,
                    "min.insync.replicas": 1,
                    "inter.broker.protocol.version": "3.9"
                },
                storage: {
                    type: "ephemeral"
                }
            },
            zookeeper: {
                replicas: 3,
                storage: {
                    type: "ephemeral"
                }
            },
            entityOperator: {
                topicOperator: {},
                userOperator: {}
            }
        }
    };
}

function makeDeployKafkaClusterKraftManifest(clusterName: BaseExpression<string>) {
    return {
        apiVersion: "kafka.strimzi.io/v1beta2",
        kind: "Kafka",
        metadata: {
            name: clusterName,
            annotations: {
                "strimzi.io/node-pools": "enabled",
                "strimzi.io/kraft": "enabled"
            }
        },
        spec: {
            kafka: {
                version: "3.9.0",
                metadataVersion: "3.9-IV0",
                readinessProbe: {
                    initialDelaySeconds: 1,
                    periodSeconds: 2,
                    timeoutSeconds: 2,
                    failureThreshold: 1
                },
                livenessProbe: {
                    initialDelaySeconds: 1,
                    periodSeconds: 2,
                    timeoutSeconds: 2,
                    failureThreshold: 2
                },
                listeners: [
                    {
                        name: "plain",
                        port: 9092,
                        type: "internal",
                        tls: false
                    },
                    {
                        name: "tls",
                        port: 9093,
                        type: "internal",
                        tls: true
                    }
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
            entityOperator: {
                topicOperator: {},
                userOperator: {}
            }
        }
    };
}

function makeDeployKafkaNodePool(clusterName: BaseExpression<string>) {
    return {
        apiVersion: "kafka.strimzi.io/v1beta2",
        kind: "KafkaNodePool",
        metadata: {
            name: "dual-role",
            labels: {
                "strimzi.io/cluster": clusterName
            }
        },
        spec: {
            replicas: 1,
            roles: [
                "controller",
                "broker"
            ],
            storage: {
                type: "jbod",
                volumes: [
                    {
                        id: 0,
                        type: "persistent-claim",
                        size: "5Gi",
                        deleteClaim: false,
                        kraftMetadata: "shared"
                    }
                ]
            }
        }
    };
}

function makeKafkaTopicManifest(args: {
    clusterName: BaseExpression<string>,
    topicName: BaseExpression<string>,
    topicPartitions: BaseExpression<Serialized<number>>,
    topicReplicas: BaseExpression<Serialized<number>>
}) {
    return {
        apiVersion: "kafka.strimzi.io/v1beta2",
        kind: "KafkaTopic",
        metadata: {
            name: args.topicName,
            labels: {
                "strimzi.io/cluster": args.clusterName,
            }
        },
        spec: {
            partitions: args.topicPartitions,
            replicas: args.topicReplicas,
            config: {
                "retention.ms": 604800000,
                "segment.bytes": 1073741824
            }
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


    .addTemplate("deployKafkaClusterZookeeper", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "create",
                setOwnerReference: true,
                successCondition: "status.listeners",
                manifest: makeDeployKafkaClusterZookeeperManifest(b.inputs.clusterName)
            }))
        .addJsonPathOutput("brokers", "{.status.listeners[?(@.name=='plain')].bootstrapServers}",
            typeToken<string>())
    )


    .addTemplate("deployKafkaNodePool", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: true,
                manifest: makeDeployKafkaNodePool(b.inputs.clusterName)
            }))
    )


    .addTemplate("deployKafkaClusterKraft", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: true,
                successCondition: "status.listeners",
                manifest: makeDeployKafkaClusterKraftManifest(b.inputs.clusterName)
            }))
        .addJsonPathOutput("brokers", "{.status.listeners[?(@.name=='plain')].bootstrapServers}",
            typeToken<string>())
    )


    .addTemplate("deployKafkaCluster", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addOptionalInput("useKraft", s => true as boolean)
        .addDag(b => b
            .addTask("deployPool", INTERNAL, "deployKafkaNodePool", c =>
                    c.register({clusterName: b.inputs.clusterName}),
                {when: expr.cast(b.inputs.useKraft).to<boolean>()})
            .addTask("deployKafkaClusterKraft", INTERNAL, "deployKafkaClusterKraft", c =>
                    c.register(selectInputsForRegister(b, c)),
                {when: expr.cast(b.inputs.useKraft).to<boolean>()})
            .addTask("deployKafkaClusterZookeeper", INTERNAL, "deployKafkaClusterZookeeper", c =>
                    c.register(selectInputsForRegister(b, c)),
                {when: expr.not(expr.cast(b.inputs.useKraft).to<boolean>())})
        )
        .addExpressionOutput("clusterName", c => c.inputs.clusterName)
        .addExpressionOutput("bootstrapServers", c =>
            expr.ternary(expr.equals(expr.literal("Skipped"), c.tasks.deployKafkaClusterKraft.status),
                c.tasks.deployKafkaClusterZookeeper.outputs.brokers,
                c.tasks.deployKafkaClusterKraft.outputs.brokers))
    )

    .addTemplate("createKafkaTopic", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("topicName", typeToken<string>())
        .addRequiredInput("topicPartitions", typeToken<number>())
        .addRequiredInput("topicReplicas", typeToken<number>())

        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: true,
                successCondition: "status.topicName",
                manifest: makeKafkaTopicManifest(b.inputs)
            }))
        .addJsonPathOutput("topicName", "{.status.topicName}", typeToken<string>())
    )


    .getFullScope();
