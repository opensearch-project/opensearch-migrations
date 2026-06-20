import {
    BaseExpression,
    expr,
    INTERNAL,
    makeDirectTypeProxy,
    makeStringTypeProxy,
    selectInputsForRegister,
    Serialized,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {OwnerReference} from "@opensearch-migrations/k8s-types";
import {CommonWorkflowParameters, workflowScriptCommand, workflowScriptRootEnvVars} from "./commonUtils/workflowParameters";
import {DEFAULT_RESOURCES, KAFKA_CLUSTER_CREATION_CONFIG} from "@opensearch-migrations/schemas";
import {z} from "zod";
import {
    KAFKA_CLUSTER_READY_TIMEOUT_SECONDS,
    KAFKA_DIAGNOSTIC_RETRY_STRATEGY,
    K8S_RESOURCE_RETRY_STRATEGY
} from "./commonUtils/resourceRetryStrategy";
import {ResourceManagement} from "./resourceManagement";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";

type KafkaConfig = z.infer<typeof KAFKA_CLUSTER_CREATION_CONFIG>;
const KAFKA_CLUSTER_LABEL = "migrations.opensearch.org/kafka-cluster";
const SOURCE_LABEL = "migrations.opensearch.org/source";
const WORKFLOW_LABEL = "workflows.argoproj.io/workflow";
const KAFKA_READY_DIAGNOSTIC_ACTIVE_DEADLINE_SECONDS = 5 * 60;
const KAFKA_DIAGNOSTIC_ARTIFACT_NAME = "kafkaReadinessDiagnostics";
const KAFKA_DIAGNOSTIC_LOG_PATH = "/tmp/kafka-readiness-diagnostics.log";

function makeOwnerReferences(
    ownerName: BaseExpression<string>,
    ownerUid: BaseExpression<string>,
): OwnerReference[] {
    return [{
        apiVersion: "migrations.opensearch.org/v1alpha1",
        kind: "KafkaCluster",
        name: makeStringTypeProxy(ownerName),
        uid: makeStringTypeProxy(ownerUid),
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
    migrationRunNumber: BaseExpression<string>,
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
                [WORKFLOW_LABEL]: makeStringTypeProxy(expr.getWorkflowValue("name")),
                "migrations.opensearch.org/run-number": makeStringTypeProxy(args.migrationRunNumber),
                [KAFKA_CLUSTER_LABEL]: makeStringTypeProxy(args.clusterName),
            }
        },
        spec: makeDirectTypeProxy(expr.deserializeRecord(args.userSpec))
    };
}

function makeDeployKafkaNodePool(args: {
    clusterName: BaseExpression<string>,
    nodePoolSpec: BaseExpression<Serialized<Record<string, any>>>,
    migrationRunNumber: BaseExpression<string>,
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
                [WORKFLOW_LABEL]: makeStringTypeProxy(expr.getWorkflowValue("name")),
                "migrations.opensearch.org/run-number": makeStringTypeProxy(args.migrationRunNumber),
                [KAFKA_CLUSTER_LABEL]: makeStringTypeProxy(args.clusterName),
            }
        },
        // Merge default broker resources UNDER the user's nodePoolSpec so brokers get a
        // 'Guaranteed' QoS by default (without it they are 'BestEffort' — see
        // DEFAULT_RESOURCES.KAFKA_BROKER). sprig.merge gives the first arg precedence, so any
        // user-supplied resources (or other fields) in nodePoolSpec override the default.
        spec: makeDirectTypeProxy(expr.mergeDicts(
            expr.deserializeRecord(args.nodePoolSpec),
            expr.makeDict({resources: expr.templateValue(DEFAULT_RESOURCES.KAFKA_BROKER)})
        ))
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
    migrationRunNumber: BaseExpression<string>,
    ownerUid: BaseExpression<string>,
}): Record<string, any> {
    return {
        apiVersion: "kafka.strimzi.io/v1",
        kind: "Kafka",
        metadata: {
            name: args.clusterName,
            ownerReferences: makeOwnerReferences(args.clusterName, args.ownerUid),
            labels: {
                "strimzi.io/cluster": makeStringTypeProxy(args.clusterName),
                [WORKFLOW_LABEL]: makeStringTypeProxy(expr.getWorkflowValue("name")),
                "migrations.opensearch.org/run-number": makeStringTypeProxy(args.migrationRunNumber),
                [KAFKA_CLUSTER_LABEL]: makeStringTypeProxy(args.clusterName),
            },
            annotations: {
                "strimzi.io/node-pools": "enabled",
                "strimzi.io/kraft": "enabled"
            }
        },
        spec: {
            kafka: makeDirectTypeProxy(expr.deserializeRecord(args.kafkaSpec)),
            // Reserve CPU/memory on both operator sidecars. Empty {} leaves them with no
            // resource requests (BestEffort), which lets the scheduler pack them onto a
            // saturated node where the JVM cold-start loses the liveness-probe race and
            // crash-loops — wedging the migration since the user-operator owns the KafkaUser
            // SCRAM secret. See DEFAULT_RESOURCES.ENTITY_OPERATOR.
            entityOperator: {
                topicOperator: {resources: DEFAULT_RESOURCES.ENTITY_OPERATOR},
                userOperator: {resources: DEFAULT_RESOURCES.ENTITY_OPERATOR}
            }
        }
    };
}

function shouldCreateManagedKafkaUser(clusterConfig: BaseExpression<Serialized<KafkaConfig>>) {
    return expr.equals(getKafkaAuthType(clusterConfig), expr.literal("scram-sha-512"));
}

function makeKafkaTopicManifest(args: {
    clusterName: BaseExpression<string>,
    topicName: BaseExpression<string>,
    migrationRunNumber: BaseExpression<string>,
    ownerUid: BaseExpression<string>,
    sourceLabel: BaseExpression<string>,
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
                [WORKFLOW_LABEL]: makeStringTypeProxy(expr.getWorkflowValue("name")),
                "migrations.opensearch.org/run-number": makeStringTypeProxy(args.migrationRunNumber),
                [KAFKA_CLUSTER_LABEL]: makeStringTypeProxy(args.clusterName),
                [SOURCE_LABEL]: makeStringTypeProxy(args.sourceLabel),
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

    .addTemplate("diagnoseKafkaClusterNotReady", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addContainer(b => b
            .addImageInfo(b.inputs.imageMigrationConsoleLocation, b.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/bash", "-lc"])
            .addResources(DEFAULT_RESOURCES.SHELL_MIGRATION_CONSOLE_CLI)
            .addEnvVarsFromRecord({
                NAMESPACE: expr.getWorkflowValue("namespace"),
                KAFKA_CLUSTER_NAME: b.inputs.resourceName,
                TIMEOUT_SECONDS: expr.literal(String(KAFKA_CLUSTER_READY_TIMEOUT_SECONDS)),
                DIAGNOSTIC_LOG_PATH: expr.literal(KAFKA_DIAGNOSTIC_LOG_PATH),
                ...workflowScriptRootEnvVars(t.inputs.workflowParameters.workflowScriptsRoot)
            })
            .addArgs([workflowScriptCommand("diagnoseKafkaClusterNotReady.sh")])
            .addActiveDeadlineSeconds(() => KAFKA_READY_DIAGNOSTIC_ACTIVE_DEADLINE_SECONDS)
            .addArtifactOutput(KAFKA_DIAGNOSTIC_ARTIFACT_NAME, KAFKA_DIAGNOSTIC_LOG_PATH, {
                s3Key: expr.concat(
                    expr.literal("diagnostics/"),
                    expr.getWorkflowValue("uid"),
                    expr.literal(`/${KAFKA_DIAGNOSTIC_ARTIFACT_NAME}`)
                )
            })
        )
        .addRetryParameters(KAFKA_DIAGNOSTIC_RETRY_STRATEGY)
    )


    .addTemplate("failKafkaClusterReadyWait", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addContainer(b => b
            .addImageInfo(b.inputs.imageMigrationConsoleLocation, b.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/bash", "-lc"])
            .addResources(DEFAULT_RESOURCES.SHELL_MIGRATION_CONSOLE_CLI)
            .addEnvVarsFromRecord({
                KAFKA_CLUSTER_NAME: b.inputs.resourceName,
            })
            // We need a failing template since we're deferring propagating the failure up 
            // so that we can first collect diagnostics.  The `exit 1` is the critical part here.
            .addArgs([`
set -euo pipefail
echo "Kafka/$KAFKA_CLUSTER_NAME did not become Ready; diagnostics were collected in the kafkaReadinessDiagnostics artifact and diagnostic pod logs" >&2
exit 1
`])
        )
    )


    .addTemplate("waitForKafkaClusterReady", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(b => b
            .addStep("waitForReady", ResourceManagement, "waitForKafkaClusterReadyResource", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: b.inputs.resourceName,
                }),
                {continueOn: {failed: true, error: true}}
            )
            .addStep("diagnoseFailure", INTERNAL, "diagnoseKafkaClusterNotReady", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: b.inputs.resourceName,
                }),
                {when: c => ({templateExp: expr.or(
                    expr.equals(c.waitForReady.status, "Failed"),
                    expr.equals(c.waitForReady.status, "Error")
                )})}
            )
            .addStep("failAfterDiagnostics", INTERNAL, "failKafkaClusterReadyWait", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: b.inputs.resourceName,
                }),
                {when: c => ({templateExp: expr.or(
                    expr.equals(c.waitForReady.status, "Failed"),
                    expr.equals(c.waitForReady.status, "Error")
                )})}
            )
        )
    )


    .addTemplate("waitForKafkaCluster", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(b => b
            .addStep("waitForCreation", ResourceManagement, "waitForKafkaClusterCreated", c =>
                c.register({...selectInputsForRegister(b, c), resourceName: b.inputs.resourceName})
            )
            .addStep("waitForReady", INTERNAL, "waitForKafkaClusterReady", c =>
                c.register({...selectInputsForRegister(b, c), resourceName: b.inputs.resourceName})
            )
        )
    )


    .addTemplate("deployKafkaNodePool", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("nodePoolSpec", typeToken<Serialized<Record<string, any>>>())
        .addRequiredInput("migrationRunNumber", typeToken<string>())
        .addRequiredInput("ownerUid", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeDeployKafkaNodePool({
                    clusterName: b.inputs.clusterName,
                    nodePoolSpec: b.inputs.nodePoolSpec,
                    migrationRunNumber: b.inputs.migrationRunNumber,
                    ownerUid: b.inputs.ownerUid,
                })
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("deployKafkaClusterKraftNoAuth", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("kafkaSpec", typeToken<Serialized<Record<string, any>>>())
        .addRequiredInput("migrationRunNumber", typeToken<string>())
        .addRequiredInput("ownerUid", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeDeployKafkaClusterKraftManifest({
                    clusterName: b.inputs.clusterName,
                    kafkaSpec: b.inputs.kafkaSpec,
                    migrationRunNumber: b.inputs.migrationRunNumber,
                    ownerUid: b.inputs.ownerUid,
                })
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("deployKafkaClusterKraftScram", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("kafkaSpec", typeToken<Serialized<Record<string, any>>>())
        .addRequiredInput("migrationRunNumber", typeToken<string>())
        .addRequiredInput("ownerUid", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeDeployKafkaClusterKraftManifest({
                    clusterName: b.inputs.clusterName,
                    kafkaSpec: b.inputs.kafkaSpec,
                    migrationRunNumber: b.inputs.migrationRunNumber,
                    ownerUid: b.inputs.ownerUid,
                })
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .addTemplate("deployKafkaCluster", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("version", typeToken<string>())
        .addRequiredInput("clusterConfig", typeToken<KafkaConfig>())
        .addRequiredInput("migrationRunNumber", typeToken<string>())
        .addRequiredInput("ownerUid", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addSteps(b => b
            .addStep("deployNoAuthCluster", INTERNAL, "deployKafkaClusterKraftNoAuth", c =>
                    c.register({
                        clusterName: b.inputs.clusterName,
                        kafkaSpec: expr.recordToString(makeManagedKafkaSpecNoAuth({
                            version: b.inputs.version,
                            clusterConfig: b.inputs.clusterConfig,
                        })),
                        migrationRunNumber: b.inputs.migrationRunNumber,
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
                        migrationRunNumber: b.inputs.migrationRunNumber,
                        ownerUid: b.inputs.ownerUid,
                    }),
                {when: c => ({templateExp: shouldCreateManagedKafkaUser(b.inputs.clusterConfig)})}
            )
            .addStep("waitForClusterReady", INTERNAL, "waitForKafkaClusterReady", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: b.inputs.clusterName,
                })
            )
        )
    )


    .addTemplate("createKafkaTopic", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("topicName", typeToken<string>())
        .addRequiredInput("migrationRunNumber", typeToken<string>())
        .addRequiredInput("ownerUid", typeToken<string>())
        .addRequiredInput("sourceLabel", typeToken<string>())
        .addRequiredInput("partitions", typeToken<number>())
        .addRequiredInput("replicas", typeToken<number>())
        .addRequiredInput("topicConfig", typeToken<Serialized<Record<string, any>>>())

        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeKafkaTopicManifest({
                    clusterName: b.inputs.clusterName,
                    topicName: b.inputs.topicName,
                    migrationRunNumber: b.inputs.migrationRunNumber,
                    ownerUid: b.inputs.ownerUid,
                    sourceLabel: b.inputs.sourceLabel,
                    partitions: b.inputs.partitions,
                    replicas: b.inputs.replicas,
                    topicConfig: b.inputs.topicConfig,
                })
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    .addTemplate("createKafkaUser", t => t
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("userSpec", typeToken<Serialized<Record<string, any>>>())
        .addRequiredInput("migrationRunNumber", typeToken<string>())
        .addRequiredInput("ownerUid", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeManagedKafkaUserManifest({
                    clusterName: b.inputs.clusterName,
                    userSpec: b.inputs.userSpec,
                    migrationRunNumber: b.inputs.migrationRunNumber,
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
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

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
                        migrationRunNumber: t.inputs.workflowParameters.migrationRunNumber,
                        ownerUid: b.inputs.ownerUid,
                    })
                )
                .addStep("deployCluster", INTERNAL, "deployKafkaCluster", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        clusterName: b.inputs.clusterName,
                        version: b.inputs.version,
                        clusterConfig: b.inputs.clusterConfig,
                        migrationRunNumber: t.inputs.workflowParameters.migrationRunNumber,
                        ownerUid: b.inputs.ownerUid,
                    })
                )
                .addStep("deployKafkaUser", INTERNAL, "createKafkaUser", c =>
                    c.register({
                        clusterName: b.inputs.clusterName,
                        userSpec: expr.recordToString(makeManagedKafkaUserSpec(b.inputs.clusterConfig)),
                        migrationRunNumber: t.inputs.workflowParameters.migrationRunNumber,
                        ownerUid: b.inputs.ownerUid,
                    }),
                    {when: c => ({templateExp: shouldCreateManagedKafkaUser(b.inputs.clusterConfig)})}
                )
                .addStep("waitForKafkaUserSecret", ResourceManagement, "waitForSecretKey", c =>
                    c.register({
                        secretName: expr.concat(b.inputs.clusterName, expr.literal("-migration-app")),
                        secretKey: expr.literal("password"),
                    }),
                    {when: c => ({templateExp: shouldCreateManagedKafkaUser(b.inputs.clusterConfig)})}
                );
        })
    )


    .getFullScope();
