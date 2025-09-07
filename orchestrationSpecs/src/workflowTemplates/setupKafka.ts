import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {CommonWorkflowParameters} from "@/workflowTemplates/commonWorkflowTemplates";
import {typeToken} from "@/schemas/parameterSchemas";
import {AllowLiteralOrExpression} from "@/schemas/workflowTypes";
import {INTERNAL} from "@/schemas/taskBuilder";

function makeDeployKafkaClusterZookeeperManifest(inputs: { kafkaName: AllowLiteralOrExpression<string> }) {
    return {
        "apiVersion": "kafka.strimzi.io/v1beta2",
        "kind": "Kafka",
        "metadata": {
            "name": inputs.kafkaName
        },
        "spec": {
            "kafka": {
                "version": "3.9.0",
                "replicas": 1,
                "listeners": [
                    {
                        "name": "tls",
                        "port": 9093,
                        "type": "internal",
                        "tls": true
                    }
                ],
                "config": {
                    "offsets.topic.replication.factor": 1,
                    "transaction.state.log.replication.factor": 1,
                    "transaction.state.log.min.isr": 1,
                    "default.replication.factor": 1,
                    "min.insync.replicas": 1,
                    "inter.broker.protocol.version": "3.9"
                },
                "storage": {
                    "type": "ephemeral"
                }
            },
            "zookeeper": {
                "replicas": 3,
                "storage": {
                    "type": "ephemeral"
                }
            },
            "entityOperator": {
                "topicOperator": {},
                "userOperator": {}
            }
        }
    };
}

function makeDeployKafkaNodePool(inputs: { kafkaName: AllowLiteralOrExpression<string> }) {
    return {
        "apiVersion": "kafka.strimzi.io/v1beta2",
        "kind": "KafkaNodePool",
        "metadata": {
            "name": "dual-role",
            "labels": {
                "strimzi.io/cluster": inputs.kafkaName
            }
        },
        "spec": {
            "replicas": 1,
            "roles": [
                "controller",
                "broker"
            ],
            "storage": {
                "type": "jbod",
                "volumes": [
                    {
                        "id": 0,
                        "type": "persistent-claim",
                        "size": "5Gi",
                        "deleteClaim": false,
                        "kraftMetadata": "shared"
                    }
                ]
            }
        }
    };
}

export const SetupKafka = WorkflowBuilder.create({
    k8sResourceName: "SetupKafka",
    serviceAccountName: "argo-workflow-executor",
    k8sMetadata: {},
    parallelism: 1
})
    .addParams(CommonWorkflowParameters)
    .addTemplate("deployKafkaNodePool", t => t
        .addRequiredInput("kafkaName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: true,
                manifest: makeDeployKafkaNodePool(b.inputs)
            }))
    )
    .addTemplate("deployKafkaClusterZookeeper", t => t
        .addRequiredInput("kafkaName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "create",
                setOwnerReference: true,
                successCondition: "status.listeners",
                manifest: makeDeployKafkaClusterZookeeperManifest(b.inputs)
            })))
    .addTemplate("clusterDeploy", t=> t
        .addRequiredInput("kafkaName", typeToken<string>())
        .addOptionalInput("useKraft", s=>true)
        .addDag(b=>b
            .addTask("deployPool", INTERNAL,"deployKafkaNodePool", c => c.register({
                    kafkaName: b.inputs.kafkaName,
            }), {when: b.inputs.useKraft}))
    )
    .getFullScope();
