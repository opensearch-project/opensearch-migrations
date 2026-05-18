import {z} from 'zod';
import {
    ARGO_METADATA_OPTIONS,
    ARGO_MIGRATION_CONFIG,
    ARGO_REPLAYER_OPTIONS,
    ARGO_RFS_OPTIONS,
    COMPLETE_SNAPSHOT_CONFIG,
    DEFAULT_RESOURCES,
    DENORMALIZED_CREATE_SNAPSHOTS_CONFIG,
    DENORMALIZED_PROXY_CONFIG,
    DENORMALIZED_REPLAY_CONFIG,
    ENRICHED_SNAPSHOT_MIGRATION_FILTER,
    getZodKeys,
    NAMED_KAFKA_CLIENT_CONFIG,
    NAMED_KAFKA_CLUSTER_CONFIG,
    NAMED_TARGET_CLUSTER_CONFIG,
    PER_SOURCE_CREATE_SNAPSHOTS_CONFIG,
    SNAPSHOT_MIGRATION_CONFIG,
} from '@opensearch-migrations/schemas'
import {
    AllowLiteralOrExpression,
    BaseExpression,
    configMapKey,
    defineParam,
    expr,
    IMAGE_PULL_POLICY,
    InputParamDef,
    INTERNAL,
    makeParameterLoop,
    selectInputsFieldsAsExpressionRecord,
    selectInputsForRegister,
    Serialized,
    typeToken,
    WorkflowBuilder
} from '@opensearch-migrations/argo-workflow-builders';
import {DocumentBulkLoad} from "./documentBulkLoad";
import {MetadataMigration} from "./metadataMigration";
import {CreateOrGetSnapshot} from "./createOrGetSnapshot";
import {ResourceManagement} from "./resourceManagement";

import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {ImageParameters, LogicalOciImages, makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {SetupKafka} from "./setupKafka";
import {SetupCapture} from "./setupCapture";
import {Replayer} from "./replayer";
import {CONTAINER_TEMPLATE_RETRY_STRATEGY} from "./commonUtils/resourceRetryStrategy";

const SECONDS_IN_DAYS = 24 * 3600;
const LONGEST_POSSIBLE_MIGRATION = 365 * SECONDS_IN_DAYS;

const uniqueRunNonceParam = {
    uniqueRunNonce: defineParam({
        expression: expr.getWorkflowValue("uid"),
        description: "Workflow session nonce"
    })
};

function lowercaseFirst(str: string): string {
    return str.charAt(0).toLowerCase() + str.slice(1);
}

function defaultImagesMap(imageConfigMapName: AllowLiteralOrExpression<string>) {
    return Object.fromEntries(LogicalOciImages.flatMap(k => [
            [`image${k}Location`, defineParam({
                type: typeToken<string>(),
                from: configMapKey(imageConfigMapName,
                    `${lowercaseFirst(k)}Image`)
            })],
            [`image${k}PullPolicy`, defineParam({
                type: typeToken<string>(),
                from: configMapKey(imageConfigMapName,
                    `${lowercaseFirst(k)}PullPolicy`)
            })]
        ])
    ) as Record<`image${typeof LogicalOciImages[number]}Location`, InputParamDef<string, false>> &
        Record<`image${typeof LogicalOciImages[number]}PullPolicy`, InputParamDef<IMAGE_PULL_POLICY, false>>;
}

const CRD_API_VERSION = "migrations.opensearch.org/v1alpha1";

function checksumNotDone(
    actualChecksum: BaseExpression<string>,
    desiredChecksum: BaseExpression<string>,
): BaseExpression<boolean, "complicatedExpression"> {
    return expr.and(expr.literal(true), expr.not(expr.equals(actualChecksum, desiredChecksum)));
}

export const FullMigration = WorkflowBuilder.create({
    k8sResourceName: "full-migration",
    parallelism: 100,
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    // ── Utility ──────────────────────────────────────────────────────────

    .addTemplate("doNothing", t => t
        .addSteps(b => b.addStepGroup(c => c)))


    .addTemplate("addApprovalGateOwnerReferences", t => t
        .addInputsFromRecord(defaultImagesMap(t.inputs.workflowParameters.imageConfigMapName))
        .addContainer(b => b
            .addImageInfo(b.inputs.imageMigrationConsoleLocation, b.inputs.imageMigrationConsolePullPolicy)
            .addResources(DEFAULT_RESOURCES.SHELL_MIGRATION_CONSOLE_CLI)
            .addCommand(["/bin/bash", "-lc"])
            .addArgs([`
set -euo pipefail

selector='migrations.opensearch.org/workflow={{workflow.name}}'
patch='{"metadata":{"ownerReferences":[{"apiVersion":"argoproj.io/v1alpha1","kind":"Workflow","name":"{{workflow.name}}","uid":"{{workflow.uid}}"}]}}'

kubectl get approvalgates.migrations.opensearch.org -l "$selector" -o name \\
  | xargs -r -n 1 kubectl patch --type merge -p "$patch" \\
  || { echo "ERROR: failed to patch one or more approvalgate ownerReferences" >&2; exit 1; }
`])
        )
        .addRetryParameters(CONTAINER_TEMPLATE_RETRY_STRATEGY)
    )


    .addTemplate("cleanupApprovalGates", t => t
        .addInputsFromRecord(defaultImagesMap(t.inputs.workflowParameters.imageConfigMapName))
        .addContainer(b => b
            .addImageInfo(b.inputs.imageMigrationConsoleLocation, b.inputs.imageMigrationConsolePullPolicy)
            .addResources(DEFAULT_RESOURCES.SHELL_MIGRATION_CONSOLE_CLI)
            .addCommand(["/bin/bash", "-lc"])
            .addArgs([`
kubectl delete approvalgates.migrations.opensearch.org \\
  -l "migrations.opensearch.org/workflow={{workflow.name}}" \\
  --ignore-not-found
`])
        )
        .addRetryParameters(CONTAINER_TEMPLATE_RETRY_STRATEGY)
    )


    // ── Section 1: Kafka Clusters ────────────────────────────────────────

    .addTemplate("setupSingleKafkaCluster", t => t
        .addRequiredInput("kafkaClusterConfig", typeToken<z.infer<typeof NAMED_KAFKA_CLUSTER_CONFIG>>())
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("version", typeToken<string>())
        .addRequiredInput("resourceUid", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addOptionalInput("groupName_view", c => "Kafka Cluster")
        .addOptionalInput("sortOrder_view", c => 999)

        .addSteps(b => {
            return b
                .addStep("reconcileKafkaClusterResource", ResourceManagement, "reconcileKafkaClusterResource", c =>
                    c.register({
                        kafkaClusterConfig: b.inputs.kafkaClusterConfig,
                        retryGateName: expr.concat(expr.literal("kafkacluster."), b.inputs.clusterName, expr.literal(".vapretry")),
                        retryGroupName_view: expr.concat(expr.literal("KafkaCluster: "), b.inputs.clusterName),
                    })
                )
                .addStep("deployCluster", SetupKafka, "deployKafkaClusterAndTopics", c =>
                    c.register({
                        clusterName: b.inputs.clusterName,
                        version: b.inputs.version,
                        clusterConfig: expr.jsonPathStrictSerialized(b.inputs.kafkaClusterConfig, "config"),
                        ownerUid: b.inputs.resourceUid,
                    }),
                    {when: c => ({templateExp: checksumNotDone(c.reconcileKafkaClusterResource.outputs.currentConfigChecksum, b.inputs.configChecksum)})}
                )
                .addStep("patchKafkaClusterReady", ResourceManagement, "patchKafkaClusterReady", c =>
                    c.register({
                        resourceName: b.inputs.clusterName,
                        phase: expr.literal("Ready"),
                        configChecksum: b.inputs.configChecksum,
                    }),
                    {when: c => ({templateExp: checksumNotDone(c.reconcileKafkaClusterResource.outputs.currentConfigChecksum, b.inputs.configChecksum)})}
                );
        })
    )


    // ── Section 2: Proxies ───────────────────────────────────────────────

    .addTemplate("setupSingleProxy", t => t
        .addRequiredInput("proxyConfig", typeToken<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>())
        .addRequiredInput("kafkaClusterName", typeToken<string>())
        .addRequiredInput("kafkaTopicName", typeToken<string>())
        .addRequiredInput("proxyName", typeToken<string>())
        .addRequiredInput("topicCrName", typeToken<string>())
        .addRequiredInput("resourceUid", typeToken<string>())
        .addRequiredInput("kafkaClusterOwnerUid", typeToken<string>())
        .addRequiredInput("listenPort", typeToken<number>())
        .addRequiredInput("podReplicas", typeToken<number>())
        .addRequiredInput("topicPartitions", typeToken<number>())
        .addRequiredInput("topicReplicas", typeToken<number>())
        .addRequiredInput("topicConfig", typeToken<Serialized<Record<string, any>>>())
        .addOptionalInput("groupName_view", c => "Proxy")
        .addOptionalInput("sortOrder_view", c => 999)
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole", "CaptureProxy"]))

        .addSteps(b => b
            .addStep("setupProxy", SetupCapture, "reconcileCaptureTopicAndProxy", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    proxyConfig: b.inputs.proxyConfig,
                    kafkaClusterName: b.inputs.kafkaClusterName,
                    kafkaTopicName: b.inputs.kafkaTopicName,
                    proxyName: b.inputs.proxyName,
                    topicCrName: b.inputs.topicCrName,
                    ownerUid: b.inputs.resourceUid,
                    kafkaClusterOwnerUid: b.inputs.kafkaClusterOwnerUid,
                    configChecksum: expr.dig(expr.deserializeRecord(b.inputs.proxyConfig), ["configChecksum"], ""),
                    topicConfigChecksum: expr.dig(expr.deserializeRecord(b.inputs.proxyConfig), ["topicConfigChecksum"], ""),
                    checksumForSnapshot: expr.dig(expr.deserializeRecord(b.inputs.proxyConfig), ["checksumForSnapshot"], ""),
                    checksumForReplayer: expr.dig(expr.deserializeRecord(b.inputs.proxyConfig), ["checksumForReplayer"], ""),
                    listenPort: b.inputs.listenPort,
                    podReplicas: b.inputs.podReplicas,
                    topicPartitions: b.inputs.topicPartitions,
                    topicReplicas: b.inputs.topicReplicas,
                    topicConfig: b.inputs.topicConfig,
                    sourceK8sLabel: expr.dig(expr.deserializeRecord(b.inputs.proxyConfig), ["sourceConfig", "label"], ""),
                })
            )
        )
    )


    // ── Section 3: Snapshots ─────────────────────────────────────────────

    .addTemplate("createSingleSnapshot", t => t
        .addRequiredInput("snapshotItemConfig",
            typeToken<z.infer<typeof PER_SOURCE_CREATE_SNAPSHOTS_CONFIG>>())
        .addRequiredInput("sourceConfig",
            typeToken<z.infer<typeof DENORMALIZED_CREATE_SNAPSHOTS_CONFIG>['sourceConfig']>())
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addOptionalInput("groupName_view", c => "Snapshot")
        .addOptionalInput("sortOrder_view", c => 999)
        .addInputsFromRecord(uniqueRunNonceParam)
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => {
            return b
            .addStep("reconcileDataSnapshotResource", ResourceManagement, "reconcileDataSnapshotResource", c =>
                c.register({
                    resourceName: b.inputs.resourceName,
                    snapshotItemConfig: b.inputs.snapshotItemConfig,
                    sourceLabel: expr.get(expr.deserializeRecord(b.inputs.sourceConfig), "label"),
                }),
            )
            .addStep("readSnapshotPhase", ResourceManagement, "readResourcePhase", c =>
                c.register({
                    resourceKind: expr.literal("DataSnapshot"),
                    resourceName: b.inputs.resourceName,
                })
            )
            .addStep("waitForProxyDeps", ResourceManagement, "waitForCaptureProxy", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        resourceName: expr.get(c.item, "name"),
                        configChecksum: expr.get(c.item, "configChecksum"),
                        checksumField: expr.literal("checksumForSnapshot"),
                    }), {
                    loopWith: makeParameterLoop(
                        expr.get(expr.deserializeRecord(b.inputs.snapshotItemConfig), "dependsOnProxySetups")),
                    when: c => ({templateExp: expr.and(
                        expr.not(expr.and(
                            expr.equals(c.readSnapshotPhase.outputs.phase, "Completed"),
                            expr.equals(c.readSnapshotPhase.outputs.configChecksum, b.inputs.configChecksum)
                        )),
                        expr.not(expr.and(
                            expr.equals(c.readSnapshotPhase.outputs.phase, "Running"),
                            expr.equals(c.readSnapshotPhase.outputs.configChecksum, b.inputs.configChecksum)
                        ))
                    )}),
                }
            )
            .addStep("waitForSnapshot", ResourceManagement, "waitForDataSnapshot", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: b.inputs.resourceName,
                    configChecksum: b.inputs.configChecksum,
                    checksumField: expr.literal("checksumForSnapshotMigration"),
                }),
                {when: c => ({templateExp: expr.and(
                    expr.equals(c.readSnapshotPhase.outputs.phase, "Running"),
                    expr.equals(c.readSnapshotPhase.outputs.configChecksum, b.inputs.configChecksum)
                )})}
            )
            .addStep("createOrGetSnapshot", CreateOrGetSnapshot, "createOrGetSnapshot", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    sourceConfig: b.inputs.sourceConfig,
                    createSnapshotConfig: expr.serialize(
                        expr.get(expr.deserializeRecord(b.inputs.snapshotItemConfig), "config")
                    ),
                    snapshotPrefix: expr.get(
                        expr.deserializeRecord(b.inputs.snapshotItemConfig), "snapshotPrefix"),
                    snapshotConfig: expr.serialize(expr.makeDict({
                        config: expr.makeDict({
                            createSnapshotConfig: expr.get(
                                expr.deserializeRecord(b.inputs.snapshotItemConfig), "config")
                        }),
                        repoConfig: expr.get(expr.deserializeRecord(b.inputs.snapshotItemConfig), "repo"),
                        label: expr.get(
                            expr.deserializeRecord(b.inputs.snapshotItemConfig), "label")
                    })),
                    semaphoreConfigMapName: expr.get(
                        expr.deserializeRecord(b.inputs.snapshotItemConfig), "semaphoreConfigMapName"),
                    semaphoreKey: expr.get(
                        expr.deserializeRecord(b.inputs.snapshotItemConfig), "semaphoreKey"),
                    configChecksum: b.inputs.configChecksum,
                }),
                {when: c => ({templateExp: expr.and(
                    expr.not(expr.equals(c.readSnapshotPhase.outputs.phase, "Completed")),
                    expr.not(expr.and(
                        expr.equals(c.readSnapshotPhase.outputs.phase, "Running"),
                        expr.equals(c.readSnapshotPhase.outputs.configChecksum, b.inputs.configChecksum)
                    ))
                )})}
            )
        })
    )


    .addTemplate("createSnapshotsForSource", t => t
        .addRequiredInput("snapshotsSourceConfig",
            typeToken<z.infer<typeof DENORMALIZED_CREATE_SNAPSHOTS_CONFIG>>())
        .addOptionalInput("groupName_view", c => "Snapshots")
        .addOptionalInput("sortOrder_view", c => 999)
        .addInputsFromRecord(uniqueRunNonceParam)
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("createSnapshot", INTERNAL, "createSingleSnapshot", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    snapshotItemConfig: expr.serialize(expr.makeDict({
                        label: expr.get(c.item, "label"),
                        snapshotPrefix: expr.get(c.item, "snapshotPrefix"),
                        config: expr.deserializeRecord(expr.get(c.item, "config")),
                        repo: expr.deserializeRecord(expr.get(c.item, "repo")),
                        semaphoreConfigMapName: expr.get(c.item, "semaphoreConfigMapName"),
                        semaphoreKey: expr.get(c.item, "semaphoreKey"),
                        dependsOnProxySetups: expr.get(
                            expr.deserializeRecord(expr.recordToString(c.item)),
                            "dependsOnProxySetups"
                        ),
                        configChecksum: expr.get(c.item, "configChecksum")
                    })),
//                    snapshotItemConfig: expr.cast(c.item).to<Serialized<z.infer<typeof PER_SOURCE_CREATE_SNAPSHOTS_CONFIG>>>(),
                    sourceConfig: expr.serialize(
                        expr.get(expr.deserializeRecord(b.inputs.snapshotsSourceConfig), "sourceConfig")
                    ),
                    resourceName: expr.concat(
                        expr.dig(
                            expr.deserializeRecord(b.inputs.snapshotsSourceConfig),
                            ["sourceConfig", "label"],
                            ""
                        ),
                        expr.literal("-"),
                        expr.get(c.item, "label")
                    ),
                    configChecksum: expr.dig(c.item, ["configChecksum"], ""),
                }), {
                    loopWith: makeParameterLoop(
                        expr.get(expr.deserializeRecord(b.inputs.snapshotsSourceConfig),
                            "createSnapshotConfig"))
                }
            )
        )
    )


    // ── Section 4: Snapshot Migrations ───────────────────────────────────

    .addTemplate("migrateFromSnapshot", t => t
        .addRequiredInput("sourceVersion", typeToken<string>())
        .addRequiredInput("sourceLabel", typeToken<string>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("migrationLabel", typeToken<string>())
        .addRequiredInput("crdName", typeToken<string>())
        .addRequiredInput("resourceUid", typeToken<string>())
        .addRequiredInput("resourceCreationTimestamp", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("groupName_view", typeToken<string>())
        .addOptionalInput("sourceEndpoint", c => expr.literal(""))
        .addOptionalInput("metadataMigrationConfig", c =>
            expr.empty<z.infer<typeof ARGO_METADATA_OPTIONS>>())
        .addOptionalInput("documentBackfillConfig", c =>
            expr.empty<z.infer<typeof ARGO_RFS_OPTIONS>>())

        .addInputsFromRecord(uniqueRunNonceParam)
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("idGenerator", INTERNAL, "doNothing")
            .addStep("metadataMigrate", MetadataMigration, "migrateMetaData", c => {
                    return c.register({
                        ...selectInputsForRegister(b, c),
                        crdName: b.inputs.crdName,
                        crdUid: b.inputs.resourceUid,
                        resourceCreationTimestamp: b.inputs.resourceCreationTimestamp,
                    });
                },
                {when: {templateExp: expr.not(expr.isEmpty(b.inputs.metadataMigrationConfig))}}
            )
            .addStep("bulkLoadDocuments", DocumentBulkLoad, "setupAndRunBulkLoad", c =>
                    c.register({
                        ...(selectInputsForRegister(b, c)),
                        sessionName: c.steps.idGenerator.id,
                        sourceVersion: b.inputs.sourceVersion,
                        sourceLabel: b.inputs.sourceLabel,
                        crdName: b.inputs.crdName,
                        crdUid: b.inputs.resourceUid
                    }),
                {when: {templateExp: expr.not(expr.isEmpty(b.inputs.documentBackfillConfig))}}
            )
        )
    )


    .addTemplate("runSingleSnapshotMigration", t => t
        .addRequiredInput("snapshotMigrationConfig", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>())
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("resourceUid", typeToken<string>())
        .addOptionalInput("groupName_view", c => "Snapshot Migration")
        .addOptionalInput("sortOrder_view", c => 999)
        .addInputsFromRecord(uniqueRunNonceParam)
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => {
            return b
            .addStep("reconcileSnapshotMigrationResource", ResourceManagement, "reconcileSnapshotMigrationResource", c => {
                    return c.register({
                        snapshotMigrationConfig: b.inputs.snapshotMigrationConfig,
                        resourceName: b.inputs.resourceName,
                        retryGateName: expr.concat(expr.literal("snapshotmigration."), b.inputs.resourceName, expr.literal(".vapretry")),
                        retryGroupName_view: expr.concat(expr.literal("SnapshotMigration: "), b.inputs.resourceName),
                    });
                },
            )
            .addStep("waitForSnapshot", ResourceManagement, "waitForDataSnapshot", c => {
                const config = expr.deserializeRecord(b.inputs.snapshotMigrationConfig);
                const snapshotNameRes = expr.get(config, "snapshotNameResolution");
                return c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: expr.ternary(
                        expr.hasKey(snapshotNameRes, "dataSnapshotResourceName"),
                        expr.getLoose(snapshotNameRes, "dataSnapshotResourceName"),
                        expr.literal("")),
                    configChecksum: expr.dig(config, ["snapshotConfigChecksum"], expr.literal("")),
                    checksumField: expr.literal("checksumForSnapshotMigration"),
                });
            }, {
                    when: c => ({templateExp: expr.and(
                        checksumNotDone(c.reconcileSnapshotMigrationResource.outputs.currentConfigChecksum, b.inputs.configChecksum),
                        expr.hasKey(
                            expr.get(
                                expr.deserializeRecord(b.inputs.snapshotMigrationConfig),
                                "snapshotNameResolution"
                            ),
                            "dataSnapshotResourceName")
                    )})
                }
            )
            .addStep("readSnapshotName", ResourceManagement, "readDataSnapshotName", c => {
                    const snapshotNameRes = expr.get(
                        expr.deserializeRecord(b.inputs.snapshotMigrationConfig),
                        "snapshotNameResolution");
                    return c.register({
                        resourceName: expr.ternary(
                            expr.hasKey(snapshotNameRes, "dataSnapshotResourceName"),
                            expr.getLoose(snapshotNameRes, "dataSnapshotResourceName"),
                            expr.literal(""))
                    });
                }, {
                    when: c => ({templateExp: expr.and(
                        checksumNotDone(c.reconcileSnapshotMigrationResource.outputs.currentConfigChecksum, b.inputs.configChecksum),
                        expr.hasKey(
                            expr.get(
                                expr.deserializeRecord(b.inputs.snapshotMigrationConfig),
                                "snapshotNameResolution"
                            ),
                            "dataSnapshotResourceName")
                    )})
                }
            )
            .addStep("migrateFromSnapshot", INTERNAL, "migrateFromSnapshot", c => {
                    const snapshotMigrationConfig = expr.deserializeRecord(b.inputs.snapshotMigrationConfig);
                    const snapshotNameResolution = expr.get(snapshotMigrationConfig, "snapshotNameResolution");
                    const resolvedSnapshotName = expr.ternary(
                        expr.hasKey(snapshotNameResolution, "externalSnapshotName"),
                        expr.getLoose(snapshotNameResolution, "externalSnapshotName"),
                        c.steps.readSnapshotName.outputs.snapshotName
                    );
                    const snapshotRepoConfig = expr.get(snapshotMigrationConfig, "snapshotConfig");

                    return c.register({
                        ...selectInputsForRegister(b, c),
                        sourceVersion: expr.get(snapshotMigrationConfig, "sourceVersion"),
                        sourceLabel: expr.get(snapshotMigrationConfig, "sourceLabel"),
                        targetConfig: expr.serialize(expr.get(snapshotMigrationConfig, "targetConfig")),
                        snapshotConfig: expr.serialize(expr.makeDict({
                            snapshotName: resolvedSnapshotName,
                            label: expr.get(snapshotRepoConfig, "label"),
                            repoConfig: expr.get(snapshotRepoConfig, "repoConfig")
                        })),
                        migrationLabel: expr.get(snapshotMigrationConfig, "migrationLabel"),
                        metadataMigrationConfig: expr.serialize(expr.dig(
                            snapshotMigrationConfig,
                            ["metadataMigrationConfig"],
                            expr.makeDict({}) as any
                        )),
                        documentBackfillConfig: expr.serialize(expr.dig(
                            snapshotMigrationConfig,
                            ["documentBackfillConfig"],
                            expr.makeDict({}) as any
                        )),
                        crdName: b.inputs.resourceName,
                        resourceUid: b.inputs.resourceUid,
                        resourceCreationTimestamp: c.steps.reconcileSnapshotMigrationResource.outputs.resourceCreationTimestamp,
                        groupName_view: expr.get(snapshotMigrationConfig, "migrationLabel"),
                        sourceEndpoint: expr.dig(snapshotMigrationConfig, ["sourceEndpoint"], "")
                    });
                }, {
                    when: c => ({templateExp: checksumNotDone(c.reconcileSnapshotMigrationResource.outputs.currentConfigChecksum, b.inputs.configChecksum)}),
                }
            )
            .addStep("patchSnapshotMigrationCompleted", ResourceManagement, "patchSnapshotMigrationCompleted",
                c => c.register({
                    resourceName: b.inputs.resourceName,
                    phase: expr.literal("Completed"),
                    configChecksum: b.inputs.configChecksum,
                    checksumForReplayer: expr.dig(
                        expr.deserializeRecord(b.inputs.snapshotMigrationConfig),
                        ["checksumForReplayer"], ""
                    ),
                }),
                {when: c => ({templateExp: checksumNotDone(c.reconcileSnapshotMigrationResource.outputs.currentConfigChecksum, b.inputs.configChecksum)})}
            )
        })
    )


    // ── Section 5: Traffic Replays ───────────────────────────────────────

    .addTemplate("runSingleReplay", t => t
        .addRequiredInput("kafkaConfig", typeToken<z.infer<typeof NAMED_KAFKA_CLIENT_CONFIG>>())
        .addRequiredInput("kafkaClusterName", typeToken<string>())
        .addRequiredInput("sourceLabel", typeToken<string>())
        .addRequiredInput("fromProxy", typeToken<string>())
        .addRequiredInput("fromProxyConfigChecksum", typeToken<string>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("replayerOptions", typeToken<z.infer<typeof ARGO_REPLAYER_OPTIONS>>())
        .addRequiredInput("useLocalStack", typeToken<boolean>())
        .addRequiredInput("name", typeToken<string>())
        .addRequiredInput("resourceUid", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("dependsOn", typeToken<string[]>())
        .addOptionalInput("groupName_view", c => "Traffic Replay")
        .addOptionalInput("sortOrder_view", c => 999)
        .addRequiredInput("dependsOnSnapshotMigrations", typeToken<z.infer<typeof ENRICHED_SNAPSHOT_MIGRATION_FILTER>[]>())

        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole", "TrafficReplayer"]))

        .addSteps(b => {
            return b
            .addStep("reconcileTrafficReplayResource", ResourceManagement, "reconcileTrafficReplayResource", c =>
                c.register({
                    name: b.inputs.name,
                    dependsOn: b.inputs.dependsOn,
                    replayerOptions: b.inputs.replayerOptions,
                    sourceLabel: b.inputs.sourceLabel,
                    targetLabel: expr.dig(expr.deserializeRecord(b.inputs.targetConfig), ["label"], ""),
                    retryGateName: expr.concat(expr.literal("trafficreplay."), b.inputs.name, expr.literal(".vapretry")),
                    retryGroupName_view: expr.concat(expr.literal("TrafficReplay: "), b.inputs.name),
                }),
            )
            .addStep("waitForSnapshotMigrationDeps", ResourceManagement, "waitForSnapshotMigration", c => {
                    return c.register({
                        ...selectInputsForRegister(b, c),
                        resourceName: expr.concat(
                            expr.asString(expr.get(c.item, "source")),
                            expr.literal("-"),
                            expr.dig(
                                expr.deserializeRecord(b.inputs.targetConfig),
                                ["label"],
                                ""
                            ),
                            expr.literal("-"),
                            expr.asString(expr.get(c.item, "snapshot")),
                            expr.literal("-"),
                            expr.asString(expr.get(c.item, "migrationLabel"))
                        ),
                        configChecksum: expr.dig(c.item, ["configChecksum"], expr.literal("")),
                        checksumField: expr.literal("checksumForReplayer"),
                    });
                }, {
                    loopWith: makeParameterLoop(expr.deserializeRecord(b.inputs.dependsOnSnapshotMigrations)),
                    when: c => ({templateExp: expr.and(
                        checksumNotDone(c.reconcileTrafficReplayResource.outputs.currentConfigChecksum, b.inputs.configChecksum),
                        expr.not(expr.isEmpty(expr.deserializeRecord(b.inputs.dependsOnSnapshotMigrations)))
                    )}),
                }
            )
            .addStep("waitForProxy", ResourceManagement, "waitForCaptureProxy", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: b.inputs.fromProxy,
                    configChecksum: b.inputs.fromProxyConfigChecksum,
                    checksumField: expr.literal("checksumForReplayer"),
                }),
                {when: c => ({templateExp: checksumNotDone(c.reconcileTrafficReplayResource.outputs.currentConfigChecksum, b.inputs.configChecksum)})}
            )
            .addStep("waitForKafkaCluster", ResourceManagement, "waitForKafkaCluster", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: b.inputs.kafkaClusterName,
                })
            , {
                when: c => ({templateExp: expr.and(
                    checksumNotDone(c.reconcileTrafficReplayResource.outputs.currentConfigChecksum, b.inputs.configChecksum),
                    expr.dig(
                        expr.deserializeRecord(b.inputs.kafkaConfig),
                        ["managedByWorkflow"],
                        false
                    )
                )}),
            })
            .addStep("readKafkaConnectionProfile", ResourceManagement, "readKafkaConnectionProfile", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: b.inputs.kafkaClusterName
                })
            , {
                when: c => ({templateExp: expr.and(
                    checksumNotDone(c.reconcileTrafficReplayResource.outputs.currentConfigChecksum, b.inputs.configChecksum),
                    expr.dig(
                        expr.deserializeRecord(b.inputs.kafkaConfig),
                        ["managedByWorkflow"],
                        false
                    )
                )}),
            })

            .addStep("deployReplayer", Replayer, "setupReplayer", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    kafkaGroupId: expr.concat(expr.literal("replayer-"),
                        expr.get(expr.deserializeRecord(b.inputs.targetConfig), "label")),
                    resolvedKafkaConnection: c.steps.readKafkaConnectionProfile.outputs.bootstrapServers,
                    resolvedKafkaListenerName: c.steps.readKafkaConnectionProfile.outputs.listenerName,
                    resolvedKafkaAuthType: c.steps.readKafkaConnectionProfile.outputs.authType,
                    name: b.inputs.name,
                    ownerUid: b.inputs.resourceUid,
                    useLocalStack: b.inputs.useLocalStack,
                }),
                {when: c => ({templateExp: checksumNotDone(c.reconcileTrafficReplayResource.outputs.currentConfigChecksum, b.inputs.configChecksum)})}
            )
            .addStep("patchTrafficReplayReady", ResourceManagement, "patchTrafficReplayReady",
                c => c.register({
                    resourceName: b.inputs.name,
                    phase: expr.literal("Ready"),
                    configChecksum: b.inputs.configChecksum,
                }),
                {when: c => ({templateExp: checksumNotDone(c.reconcileTrafficReplayResource.outputs.currentConfigChecksum, b.inputs.configChecksum)})}
            )
        })
    )


    // ── Main ─────────────────────────────────────────────────────────────

    .addTemplate("main", t => t
        .addRequiredInput("config",
            typeToken<z.infer<typeof ARGO_MIGRATION_CONFIG>>(),
            "Full migration configuration")
        .addRequiredInput("uniqueRunNonce", typeToken<string>())
        .addInputsFromRecord(defaultImagesMap(t.inputs.workflowParameters.imageConfigMapName))

        .addSteps(b => b.addStepGroup(g => g
            .addStep("addApprovalGateOwnerReferences", INTERNAL, "addApprovalGateOwnerReferences", c =>
                c.register({})
            )
            .addStep("createKafka", INTERNAL, "setupSingleKafkaCluster", c =>
                c.register({
                    kafkaClusterConfig: expr.serialize(expr.makeDict({
                        name: expr.get(c.item, "name"),
                        version: expr.get(c.item, "version"),
                        config: expr.deserializeRecord(expr.get(c.item, "config")),
                        topics: expr.deserializeRecord(expr.get(c.item, "topics")),
                        configChecksum: expr.get(c.item, "configChecksum"),
                        resourceUid: expr.get(c.item, "resourceUid"),
                    })),
                    //kafkaClusterConfig: expr.cast(c.item).to<Serialized<z.infer<typeof NAMED_KAFKA_CLUSTER_CONFIG>>>(),
                    clusterName: expr.get(c.item, "name"),
                    version: expr.get(c.item, "version"),
                    resourceUid: expr.get(c.item, "resourceUid"),
                    configChecksum: expr.dig(c.item, ["configChecksum"], ""),
                    groupName_view: expr.get(c.item, "name"),
                    sortOrder_view: expr.literal(1),
                }), {
                    loopWith: makeParameterLoop(
                        expr.ternary(
                            expr.hasKey(expr.deserializeRecord(b.inputs.config), "kafkaClusters"),
                            expr.getLoose(expr.deserializeRecord(b.inputs.config), "kafkaClusters"),
                            expr.literal([])
                        )),
                    when: {templateExp: expr.hasKey(expr.deserializeRecord(b.inputs.config), "kafkaClusters")}
                }
            )
            .addStep("createProxy", INTERNAL, "setupSingleProxy", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    proxyConfig: expr.serialize(expr.makeDict({
                        name: expr.get(c.item, "name"),
                        sourceConfig: expr.deserializeRecord(expr.get(c.item, "sourceConfig")),
                        kafkaConfig: expr.deserializeRecord(expr.get(c.item, "kafkaConfig")),
                        proxyConfig: expr.deserializeRecord(expr.get(c.item, "proxyConfig")),
                        configChecksum: expr.dig(c.item, ["configChecksum"], ""),
                        topicConfigChecksum: expr.dig(c.item, ["topicConfigChecksum"], ""),
                        checksumForSnapshot: expr.dig(c.item, ["checksumForSnapshot"], ""),
                        checksumForReplayer: expr.dig(c.item, ["checksumForReplayer"], ""),
                        resourceUid: expr.get(c.item, "resourceUid"),
                    })),
                    // proxyConfig:      expr.cast(c.item).to<Serialized<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>>(),
                    kafkaClusterName: expr.dig(
                        expr.deserializeRecord(expr.get(c.item, "kafkaConfig")),
                        ["label"],
                        ""
                    ),
                    kafkaTopicName: expr.dig(
                        expr.deserializeRecord(expr.get(c.item, "kafkaConfig")),
                        ["kafkaTopic"],
                        ""
                    ),
                    proxyName: expr.get(c.item, "name"),
                    topicCrName: expr.concat(expr.get(c.item, "name"), expr.literal("-topic")),
                    resourceUid: expr.get(c.item, "resourceUid"),
                    kafkaClusterOwnerUid: expr.dig(
                        expr.deserializeRecord(expr.get(c.item, "kafkaConfig")),
                        ["clusterResourceUid"],
                        ""
                    ),
                    listenPort: expr.dig(
                        expr.deserializeRecord(expr.get(c.item, "proxyConfig")),
                        ["listenPort"],
                        9200
                    ),
                    podReplicas: expr.dig(
                        expr.deserializeRecord(expr.get(c.item, "proxyConfig")),
                        ["podReplicas"],
                        1
                    ),
                    topicPartitions: expr.dig(
                        expr.deserializeRecord(expr.get(c.item, "kafkaConfig")),
                        ["topicSpecOverrides", "partitions"],
                        1
                    ),
                    topicReplicas: expr.dig(
                        expr.deserializeRecord(expr.get(c.item, "kafkaConfig")),
                        ["topicSpecOverrides", "replicas"],
                        1
                    ),
                    topicConfig: expr.serialize(expr.dig(
                        expr.deserializeRecord(expr.get(c.item, "kafkaConfig")),
                        ["topicSpecOverrides", "config"],
                        expr.makeDict({})
                    )),
                    groupName_view: expr.get(c.item, "name"),
                    sortOrder_view: expr.literal(2),
                }), {
                    loopWith: makeParameterLoop(
                        expr.get(expr.deserializeRecord(b.inputs.config), "proxies"))
                }
            )
            .addStep("createSnapshot", INTERNAL, "createSnapshotsForSource", c =>
                c.register({
                        ...selectInputsForRegister(b, c),
                        snapshotsSourceConfig: expr.serialize(expr.makeDict({
                            createSnapshotConfig: expr.deserializeRecord(expr.get(c.item, "createSnapshotConfig")),
                            sourceConfig: expr.deserializeRecord(expr.get(c.item, "sourceConfig"))
                        })),
                        //snapshotsSourceConfig: expr.cast(c.item).to<Serialized<z.infer<typeof DENORMALIZED_CREATE_SNAPSHOTS_CONFIG>>>()
                        groupName_view: expr.get(expr.deserializeRecord(expr.get(c.item, "sourceConfig")), "label"),
                        sortOrder_view: expr.literal(3),
                    }), {
                    loopWith: makeParameterLoop(
                        expr.get(expr.deserializeRecord(b.inputs.config), "snapshots"))
                }
            )
            .addStep("performSnapshotMigration", INTERNAL, "runSingleSnapshotMigration", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: expr.concat(
                        expr.get(c.item, "sourceLabel"),
                        expr.literal("-"),
                        expr.dig(
                            expr.deserializeRecord(expr.get(c.item, "targetConfig")),
                            ["label"],
                            ""
                        ),
                        expr.literal("-"),
                        expr.get(c.item, "label"),
                        expr.literal("-"),
                        expr.get(c.item, "migrationLabel")
                    ),
                    groupName_view: expr.concat(
                        expr.get(c.item, "sourceLabel"),
                        expr.literal(" → "),
                        expr.dig(
                            expr.deserializeRecord(expr.get(c.item, "targetConfig")),
                            ["label"],
                            ""
                        )
                    ),
                    snapshotMigrationConfig: expr.cast(c.item).to<Serialized<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>>(),
                    configChecksum: expr.dig(c.item, ["configChecksum"], ""),
                    resourceUid: expr.get(c.item, "resourceUid"),
                    sortOrder_view: expr.literal(4),
                }), {
                    loopWith: makeParameterLoop(
                        expr.get(expr.deserializeRecord(b.inputs.config), "snapshotMigrations"))
                }
            )
            .addStep("createTrafficReplayer", INTERNAL, "runSingleReplay", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        ...selectInputsFieldsAsExpressionRecord(c.item, c, getZodKeys(DENORMALIZED_REPLAY_CONFIG)),
                        targetConfig: expr.get(c.item, "toTarget"),
                        replayerOptions: expr.get(c.item, "replayerConfig"),
                        useLocalStack: expr.dig(
                            expr.deserializeRecord(expr.get(c.item, "replayerConfig")),
                            ["useLocalStack"],
                            false
                        ),
                        groupName_view: expr.concat(
                            expr.get(c.item, "fromProxy"),
                            expr.literal(" → "),
                            expr.dig(
                                expr.deserializeRecord(expr.get(c.item, "toTarget")),
                                ["label"],
                                ""
                            )
                        ),
                        sortOrder_view: expr.literal(5),
                    }), {
                    loopWith: makeParameterLoop(
                        expr.get(expr.deserializeRecord(b.inputs.config), "trafficReplays"))
                }
            )
        ))
    )


    .setEntrypoint("main")
    .setOnExit("cleanupApprovalGates")
    .getFullScope();
