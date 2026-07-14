import {z} from 'zod';
import {
    ARGO_METADATA_OPTIONS,
    ARGO_MIGRATION_CONFIG,
    ARGO_REPLAYER_OPTIONS,
    ARGO_RFS_OPTIONS,
    CLUSTER_CONNECTION_IDENTITY,
    COMPLETE_SNAPSHOT_CONFIG,
    DEFAULT_RESOURCES,
    DENORMALIZED_CREATE_SNAPSHOTS_CONFIG,
    DENORMALIZED_PROXY_CONFIG,
    DENORMALIZED_PROXY_SETUP_CONFIG,
    DENORMALIZED_REPLAY_CONFIG,
    DENORMALIZED_S3_TRAFFIC_LOADER_CONFIG,
    ENRICHED_SNAPSHOT_MIGRATION_FILTER,
    getZodKeys,
    NAMED_KAFKA_CLIENT_CONFIG,
    NAMED_KAFKA_CLUSTER_CONFIG,
    NAMED_SOURCE_CLUSTER_CONFIG_WITHOUT_SNAPSHOT_INFO,
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

import {CommonWorkflowParameters, workflowScriptCommand, workflowScriptRootEnvVars} from "./commonUtils/workflowParameters";
import {ImageParameters, LogicalOciImages, makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {SetupKafka} from "./setupKafka";
import {SetupCapture} from "./setupCapture";
import {S3TrafficLoader} from "./s3TrafficLoader";
import {Replayer} from "./replayer";
import {CONTAINER_TEMPLATE_RETRY_STRATEGY} from "./commonUtils/resourceRetryStrategy";
import {SCALABLE_WORKLOAD_INPUTS, scalingFromOptions} from "./commonUtils/scalableWorkload";

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


    .addTemplate("initializeRunMetadata", t => t
        .addInputsFromRecord(defaultImagesMap(t.inputs.workflowParameters.imageConfigMapName))
        .addContainer(b => b
            .addImageInfo(b.inputs.imageMigrationConsoleLocation, b.inputs.imageMigrationConsolePullPolicy)
            .addResources(DEFAULT_RESOURCES.SHELL_MIGRATION_CONSOLE_CLI)
            .addCommand(["/bin/bash", "-lc"])
            .addEnvVarsFromRecord({
                WORKFLOW_NAME: expr.getWorkflowValue("name"),
                WORKFLOW_UID: expr.getWorkflowValue("uid"),
                WORKFLOW_CREATION_TIMESTAMP: expr.getWorkflowValue("creationTimestamp"),
                MIGRATION_RUN_NUMBER: t.inputs.workflowParameters.migrationRunNumber,
                ...workflowScriptRootEnvVars(t.inputs.workflowParameters.workflowScriptsRoot)
            })
            .addArgs([workflowScriptCommand("initializeRunMetadata.sh")])
        )
        .addRetryParameters(CONTAINER_TEMPLATE_RETRY_STRATEGY)
    )


    .addTemplate("cleanupApprovalGates", t => t
        .addInputsFromRecord(defaultImagesMap(t.inputs.workflowParameters.imageConfigMapName))
        .addContainer(b => b
            .addImageInfo(b.inputs.imageMigrationConsoleLocation, b.inputs.imageMigrationConsolePullPolicy)
            .addResources(DEFAULT_RESOURCES.SHELL_MIGRATION_CONSOLE_CLI)
            .addCommand(["/bin/bash", "-lc"])
            .addEnvVarsFromRecord({
                WORKFLOW_NAME: expr.getWorkflowValue("name"),
                ...workflowScriptRootEnvVars(t.inputs.workflowParameters.workflowScriptsRoot)
            })
            .addArgs([workflowScriptCommand("cleanupApprovalGates.sh")])
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
        .addOptionalInput("resourceName", c => "")
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addSteps(b => {
            return b
                .addStep("reconcileKafkaClusterResource", ResourceManagement, "reconcileKafkaClusterResource", c =>
                    c.register({
                        kafkaClusterConfig: b.inputs.kafkaClusterConfig,
                        configChecksum: b.inputs.configChecksum,
                        retryGateName: expr.concat(expr.literal("kafkacluster."), b.inputs.clusterName, expr.literal(".vapretry")),
                        retryGroupName_view: expr.concat(expr.literal("KafkaCluster: "), b.inputs.clusterName),
                    })
                )
                .addStep("deployCluster", SetupKafka, "deployKafkaClusterAndTopics", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
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
        .addRequiredInput("proxyConfig", typeToken<z.infer<typeof DENORMALIZED_PROXY_SETUP_CONFIG>>())
        .addRequiredInput("kafkaClusterName", typeToken<string>())
        .addRequiredInput("kafkaTopicName", typeToken<string>())
        .addRequiredInput("proxyName", typeToken<string>())
        .addRequiredInput("topicCrName", typeToken<string>())
        .addRequiredInput("resourceUid", typeToken<string>())
        .addRequiredInput("kafkaClusterOwnerUid", typeToken<string>())
        .addRequiredInput("listenPort", typeToken<number>())
        .addOptionalInput("skipApproval", c => expr.literal(false))
        .addInputsFromRecord(SCALABLE_WORKLOAD_INPUTS)
        .addRequiredInput("topicPartitions", typeToken<number>())
        .addRequiredInput("topicReplicas", typeToken<number>())
        .addRequiredInput("topicConfig", typeToken<Serialized<Record<string, any>>>())
        .addOptionalInput("groupName_view", c => "Proxy")
        .addOptionalInput("sortOrder_view", c => 999)
        .addOptionalInput("resourceName", c => "")
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
                    skipApproval: b.inputs.skipApproval,
                    podReplicas: b.inputs.podReplicas,
                    minPodReplicas: b.inputs.minPodReplicas,
                    topicPartitions: b.inputs.topicPartitions,
                    topicReplicas: b.inputs.topicReplicas,
                    topicConfig: b.inputs.topicConfig,
                    sourceK8sLabel: expr.dig(expr.deserializeRecord(b.inputs.proxyConfig), ["sourceConfig", "label"], ""),
                })
            )
        )
    )


    // ── Section 2b: S3 traffic sources ───────────────────────────────────
    //
    // Parallel to setupSingleProxy, but for one-time S3 → Kafka loads.
    // Creates the same kind of CapturedTraffic CR (so the replayer wait is
    // uniform) but does NOT stand up a CaptureProxy. The loader patches the
    // CR to phase=Ready with loadCompleted=true on success.

    .addTemplate("setupSingleS3Source", t => t
        .addRequiredInput("loaderConfig", typeToken<z.infer<typeof DENORMALIZED_S3_TRAFFIC_LOADER_CONFIG>>())
        .addRequiredInput("kafkaClusterName", typeToken<string>())
        .addRequiredInput("kafkaTopicName", typeToken<string>())
        .addRequiredInput("topicCrName", typeToken<string>())
        .addRequiredInput("kafkaClusterOwnerUid", typeToken<string>())
        .addRequiredInput("topicPartitions", typeToken<number>())
        .addRequiredInput("topicReplicas", typeToken<number>())
        .addRequiredInput("topicConfig", typeToken<Serialized<Record<string, any>>>())
        .addOptionalInput("groupName_view", c => "S3 Source")
        .addOptionalInput("sortOrder_view", c => 999)
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addSteps(b => b
            .addStep("setupS3Source", S3TrafficLoader, "reconcileCapturedTrafficAndLoad", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    loaderConfig: b.inputs.loaderConfig,
                    kafkaClusterName: b.inputs.kafkaClusterName,
                    kafkaTopicName: b.inputs.kafkaTopicName,
                    topicCrName: b.inputs.topicCrName,
                    kafkaClusterOwnerUid: b.inputs.kafkaClusterOwnerUid,
                    configChecksum: expr.dig(expr.deserializeRecord(b.inputs.loaderConfig), ["configChecksum"], ""),
                    topicConfigChecksum: expr.dig(expr.deserializeRecord(b.inputs.loaderConfig), ["topicConfigChecksum"], ""),
                    checksumForSnapshot: expr.literal(""),
                    checksumForReplayer: expr.dig(expr.deserializeRecord(b.inputs.loaderConfig), ["checksumForReplayer"], ""),
                    topicPartitions: b.inputs.topicPartitions,
                    topicReplicas: b.inputs.topicReplicas,
                    topicConfig: b.inputs.topicConfig,
                    sourceK8sLabel: expr.dig(expr.deserializeRecord(b.inputs.loaderConfig), ["sourceLabel"], ""),
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
                    configChecksum: b.inputs.configChecksum,
                    retryGateName: expr.concat(expr.literal("datasnapshot."), b.inputs.resourceName, expr.literal(".vapretry")),
                }),
            )
            .addStep("readSnapshotPhase", ResourceManagement, "readResourcePhase", c =>
                c.register({
                    resourceKind: expr.literal("DataSnapshot"),
                    resourceName: b.inputs.resourceName,
                })
            )
            .addStep("waitIndefinitelyForProxyDeps", ResourceManagement, "waitIndefinitelyForCaptureProxy", c =>
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
                            expr.equals(c.readSnapshotPhase.outputs.phase, "Pending"),
                            expr.equals(c.readSnapshotPhase.outputs.configChecksum, b.inputs.configChecksum)
                        ))
                    )}),
                }
            )
            .addStep("waitIndefinitelyForSnapshot", ResourceManagement, "waitIndefinitelyForDataSnapshot", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: b.inputs.resourceName,
                    configChecksum: b.inputs.configChecksum,
                    checksumField: expr.literal("checksumForSnapshotMigration"),
                }),
                {when: c => ({templateExp: expr.and(
                    expr.equals(c.readSnapshotPhase.outputs.phase, "Pending"),
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
                    dataSnapshotName: b.inputs.resourceName,
                    dataSnapshotUid: expr.get(expr.deserializeRecord(b.inputs.snapshotItemConfig), "resourceUid"),
                    // Solr import-prepare: empty for the normal create path. When set, createOrGetSnapshot
                    // runs the import workflow (schema upload, no backup) against this external backup.
                    solrExternalBackupName: expr.dig(
                        expr.deserializeRecord(b.inputs.snapshotItemConfig), ["solrExternalBackupName"], ""),
                }),
                {when: c => ({templateExp: expr.and(
                    expr.not(expr.equals(c.readSnapshotPhase.outputs.phase, "Completed")),
                    expr.not(expr.and(
                        expr.equals(c.readSnapshotPhase.outputs.phase, "Pending"),
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
                        sourceConnectionIdentity: expr.deserializeRecord(expr.get(c.item, "sourceConnectionIdentity")),
                        config: expr.deserializeRecord(expr.get(c.item, "config")),
                        repo: expr.deserializeRecord(expr.get(c.item, "repo")),
                        semaphoreConfigMapName: expr.get(c.item, "semaphoreConfigMapName"),
                        semaphoreKey: expr.get(c.item, "semaphoreKey"),
                        dependsOnProxySetups: expr.get(
                            expr.deserializeRecord(expr.recordToString(c.item)),
                            "dependsOnProxySetups"
                        ),
                        // Resolved dependency-graph edge names written to the DataSnapshot CR spec.dependsOn.
                        dependsOn: expr.get(c.item, "dependsOn"),
                        configChecksum: expr.get(c.item, "configChecksum"),
                        resourceUid: expr.get(c.item, "resourceUid"),
                        // Carried through for the Solr import-prepare path (absent for create items).
                        solrExternalBackupName: expr.dig(c.item, ["solrExternalBackupName"], ""),
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
        .addOptionalInput("sourceConfig", c =>
            expr.empty<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG_WITHOUT_SNAPSHOT_INFO>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("migrationLabel", typeToken<string>())
        .addRequiredInput("crdName", typeToken<string>())
        .addRequiredInput("resourceUid", typeToken<string>())
        .addRequiredInput("resourceCreationTimestamp", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("checksumForReplayer", typeToken<string>())
        .addRequiredInput("workloadIdentityChecksum", typeToken<string>())
        .addRequiredInput("groupName_view", typeToken<string>())
        .addOptionalInput("sourceEndpoint", c => expr.literal(""))
        .addOptionalInput("metadataMigrationConfig", c =>
            expr.empty<z.infer<typeof ARGO_METADATA_OPTIONS>>())
        .addOptionalInput("documentBackfillConfig", c =>
            expr.empty<z.infer<typeof ARGO_RFS_OPTIONS>>())

        .addInputsFromRecord(uniqueRunNonceParam)
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
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
                        sessionName: expr.concat(expr.literal("rfs-"), b.inputs.workloadIdentityChecksum),
                        sourceVersion: b.inputs.sourceVersion,
                        sourceLabel: b.inputs.sourceLabel,
                        crdName: b.inputs.crdName,
                        crdUid: b.inputs.resourceUid,
                        configChecksum: b.inputs.configChecksum,
                        checksumForReplayer: b.inputs.checksumForReplayer
                    }),
                {when: {templateExp: expr.not(expr.isEmpty(b.inputs.documentBackfillConfig))}}
            )
            .addStep("approveBackfill", DocumentBulkLoad, "approveBackfill", c =>
                    c.register({
                        name: expr.concat(expr.literal("documentbackfill."), b.inputs.crdName)
                    }),
                {when: {templateExp: expr.and(
                    expr.not(expr.isEmpty(b.inputs.documentBackfillConfig)),
                    expr.not(expr.dig(
                        expr.deserializeRecord(b.inputs.documentBackfillConfig),
                        ["skipApproval"],
                        false
                    ))
                )}}
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
                        configChecksum: b.inputs.configChecksum,
                        retryGateName: expr.concat(expr.literal("snapshotmigration."), b.inputs.resourceName, expr.literal(".vapretry")),
                        retryGroupName_view: expr.concat(expr.literal("SnapshotMigration: "), b.inputs.resourceName),
                    });
                },
            )
            .addStep("waitIndefinitelyForSnapshot", ResourceManagement, "waitIndefinitelyForDataSnapshot", c => {
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
                        sourceConfig: expr.serialize(expr.makeDict({
                            label: expr.get(snapshotMigrationConfig, "sourceLabel"),
                            version: expr.get(snapshotMigrationConfig, "sourceVersion"),
                            endpoint: expr.dig(snapshotMigrationConfig, ["sourceEndpoint"], ""),
                            allowInsecure: expr.dig(snapshotMigrationConfig, ["sourceAllowInsecure"], false),
                            authConfig: expr.dig(snapshotMigrationConfig, ["sourceAuth"], expr.makeDict({}))
                        })),
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
                        // Use the apiserver-assigned UID emitted by reconcileSnapshotMigrationResource
                        // (rather than b.inputs.resourceUid, which may be a placeholder like "imported"
                        // for BYOS/imported-cluster flows). This is required so ownerReferences on
                        // RFS coordinator Secret/Service/StatefulSet point at the real owner UID;
                        // otherwise Kubernetes GC deletes those resources within ~1s.
                        resourceUid: c.steps.reconcileSnapshotMigrationResource.outputs.resourceUid,
                        resourceCreationTimestamp: c.steps.reconcileSnapshotMigrationResource.outputs.resourceCreationTimestamp,
                        groupName_view: expr.get(snapshotMigrationConfig, "migrationLabel"),
                        sourceEndpoint: expr.dig(snapshotMigrationConfig, ["sourceEndpoint"], ""),
                        checksumForReplayer: expr.dig(snapshotMigrationConfig, ["checksumForReplayer"], ""),
                        workloadIdentityChecksum: expr.get(snapshotMigrationConfig, "workloadIdentityChecksum")
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

    .addTemplate("runSingleReplay", t => t
        .addRequiredInput("kafkaConfig", typeToken<z.infer<typeof NAMED_KAFKA_CLIENT_CONFIG>>())
        .addRequiredInput("kafkaClusterName", typeToken<string>())
        .addRequiredInput("sourceLabel", typeToken<string>())
        .addRequiredInput("fromCapturedTraffic", typeToken<string>())
        .addRequiredInput("fromCapturedTrafficSourceKind", typeToken<"proxy" | "s3">())
        .addRequiredInput("fromCapturedTrafficConfigChecksum", typeToken<string>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("targetConnectionIdentity", typeToken<z.infer<typeof CLUSTER_CONNECTION_IDENTITY>>())
        .addRequiredInput("replayerOptions", typeToken<z.infer<typeof ARGO_REPLAYER_OPTIONS>>())
        .addRequiredInput("useLocalStack", typeToken<boolean>())
        .addRequiredInput("name", typeToken<string>())
        .addRequiredInput("resourceUid", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("dependsOn", typeToken<string[]>())
        .addOptionalInput("groupName_view", c => "Traffic Replay")
        .addOptionalInput("sortOrder_view", c => 999)
        .addOptionalInput("resourceName", c => "")
        .addRequiredInput("dependsOnSnapshotMigrations", typeToken<z.infer<typeof ENRICHED_SNAPSHOT_MIGRATION_FILTER>[]>())

        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole", "TrafficReplayer"]))

        .addSteps(b => {
            return b
            .addStep("reconcileTrafficReplayResource", ResourceManagement, "reconcileTrafficReplayResource", c =>
                c.register({
                    name: b.inputs.name,
                    dependsOn: b.inputs.dependsOn,
                    fromCapturedTraffic: b.inputs.fromCapturedTraffic,
                    fromCapturedTrafficSourceKind: b.inputs.fromCapturedTrafficSourceKind,
                    replayerOptions: b.inputs.replayerOptions,
                    sourceLabel: b.inputs.sourceLabel,
                    targetLabel: expr.dig(expr.deserializeRecord(b.inputs.targetConfig), ["label"], ""),
                    targetConnectionIdentity: b.inputs.targetConnectionIdentity,
                    configChecksum: b.inputs.configChecksum,
                    retryGateName: expr.concat(expr.literal("trafficreplay."), b.inputs.name, expr.literal(".vapretry")),
                    retryGroupName_view: expr.concat(expr.literal("TrafficReplay: "), b.inputs.name),
                }),
            )
            .addStep("waitIndefinitelyForSnapshotMigrationDeps", ResourceManagement, "waitIndefinitelyForSnapshotMigration", c => {
                    return c.register({
                        ...selectInputsForRegister(b, c),
                        resourceName: expr.asString(expr.get(c.item, "resourceName")),
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
            .addStep("waitIndefinitelyForTrafficSource", ResourceManagement, "waitIndefinitelyForTrafficSource", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    sourceName: b.inputs.fromCapturedTraffic,
                    sourceKind: b.inputs.fromCapturedTrafficSourceKind,
                    configChecksum: b.inputs.fromCapturedTrafficConfigChecksum,
                }),
                {when: c => ({templateExp: checksumNotDone(c.reconcileTrafficReplayResource.outputs.currentConfigChecksum, b.inputs.configChecksum)})}
            )
            .addStep("waitForKafkaCluster", SetupKafka, "waitForKafkaCluster", c =>
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
                .addStep("initializeRunMetadata", INTERNAL, "initializeRunMetadata", c =>
                    c.register({})
                )
            .addStep("createKafka", INTERNAL, "setupSingleKafkaCluster", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
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
                    resourceName: expr.get(c.item, "name"),
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
            .addStep("createProxy", INTERNAL, "setupSingleProxy", c => {
                const proxyScaling = scalingFromOptions(expr.get(c.item, "proxyConfig"));
                return c.register({
                    ...selectInputsForRegister(b, c),
                    proxyConfig: expr.serialize(expr.makeDict({
                        name: expr.get(c.item, "name"),
                        sourceConfig: expr.deserializeRecord(expr.get(c.item, "sourceConfig")),
                        sourceConnectionIdentity: expr.deserializeRecord(expr.get(c.item, "sourceConnectionIdentity")),
                        kafkaConfig: expr.deserializeRecord(expr.get(c.item, "kafkaConfig")),
                        proxyConfig: expr.deserializeRecord(expr.get(c.item, "proxyConfig")),
                        configChecksum: expr.dig(c.item, ["configChecksum"], ""),
                        topicConfigChecksum: expr.dig(c.item, ["topicConfigChecksum"], ""),
                        checksumForSnapshot: expr.dig(c.item, ["checksumForSnapshot"], ""),
                        checksumForReplayer: expr.dig(c.item, ["checksumForReplayer"], ""),
                        resourceUid: expr.get(c.item, "resourceUid"),
                    })),
                    skipApproval: expr.dig(c.item, ["skipApproval"], expr.literal(false)),
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
                    podReplicas: proxyScaling.podReplicas,
                    minPodReplicas: proxyScaling.minPodReplicas,
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
                    resourceName: expr.get(c.item, "name"),
                    sortOrder_view: expr.literal(2),
                });
            }, {
                    loopWith: makeParameterLoop(
                        expr.get(expr.deserializeRecord(b.inputs.config), "proxies"))
                }
            )
            .addStep("createS3Source", INTERNAL, "setupSingleS3Source", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    loaderConfig: expr.serialize(expr.makeDict({
                        name: expr.get(c.item, "name"),
                        sourceLabel: expr.get(c.item, "sourceLabel"),
                        s3Uri: expr.get(c.item, "s3Uri"),
                        awsRegion: expr.get(c.item, "awsRegion"),
                        endpoint: expr.dig(c.item, ["endpoint"], ""),
                        kafkaConfig: expr.deserializeRecord(expr.get(c.item, "kafkaConfig")),
                        configChecksum: expr.dig(c.item, ["configChecksum"], ""),
                        topicConfigChecksum: expr.dig(c.item, ["topicConfigChecksum"], ""),
                        checksumForReplayer: expr.dig(c.item, ["checksumForReplayer"], ""),
                        kafkaClusterName: expr.dig(
                            expr.deserializeRecord(expr.get(c.item, "kafkaConfig")), ["label"], ""),
                        resourceUid: expr.dig(c.item, ["resourceUid"], ""),
                    })),
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
                    topicCrName: expr.concat(expr.get(c.item, "name"), expr.literal("-topic")),
                    kafkaClusterOwnerUid: expr.dig(
                        expr.deserializeRecord(expr.get(c.item, "kafkaConfig")),
                        ["clusterResourceUid"],
                        ""
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
                        expr.dig(expr.deserializeRecord(b.inputs.config), ["s3TrafficLoaders"], expr.literal([])))
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
                    resourceName: expr.get(c.item, "resourceName"),
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
                        resourceName: expr.get(c.item, "name"),
                        useLocalStack: expr.dig(
                            expr.deserializeRecord(expr.get(c.item, "replayerConfig")),
                            ["useLocalStack"],
                            false
                        ),
                        groupName_view: expr.concat(
                            expr.get(c.item, "fromCapturedTraffic"),
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
