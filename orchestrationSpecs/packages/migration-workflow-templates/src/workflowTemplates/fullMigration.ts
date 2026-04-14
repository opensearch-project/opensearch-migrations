import {z} from 'zod';
import {
    ARGO_METADATA_OPTIONS,
    ARGO_MIGRATION_CONFIG,
    ARGO_REPLAYER_OPTIONS,
    ARGO_RFS_OPTIONS,
    COMPLETE_SNAPSHOT_CONFIG,
    DENORMALIZED_CREATE_SNAPSHOTS_CONFIG,
    DENORMALIZED_PROXY_CONFIG,
    DENORMALIZED_REPLAY_CONFIG,
    getZodKeys,
    ENRICHED_SNAPSHOT_MIGRATION_FILTER,
    NAMED_KAFKA_CLIENT_CONFIG,
    NAMED_KAFKA_CLUSTER_CONFIG,
    NAMED_TARGET_CLUSTER_CONFIG,
    PER_INDICES_SNAPSHOT_MIGRATION_CONFIG,
    PER_SOURCE_CREATE_SNAPSHOTS_CONFIG,
    SNAPSHOT_MIGRATION_CONFIG,
} from '@opensearch-migrations/schemas'
import {
    AllowLiteralOrExpression,
    configMapKey,
    defineParam,
    expr,
    IMAGE_PULL_POLICY,
    InputParamDef,
    INTERNAL,
    makeParameterLoop,
    selectInputsFieldsAsExpressionRecord,
    selectInputsForRegister,
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

export const FullMigration = WorkflowBuilder.create({
    k8sResourceName: "full-migration",
    parallelism: 100,
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    // ── Utility ──────────────────────────────────────────────────────────

    .addTemplate("doNothing", t => t
        .addSteps(b => b.addStepGroup(c => c)))


    // ── Section 1: Kafka Clusters ────────────────────────────────────────

    .addTemplate("setupSingleKafkaCluster", t => t
        .addRequiredInput("kafkaClusterConfig", typeToken<z.infer<typeof NAMED_KAFKA_CLUSTER_CONFIG>>())
        .addRequiredInput("clusterName", typeToken<string>())
        .addRequiredInput("version", typeToken<string>())
        .addOptionalInput("groupName_view", c => "Kafka Cluster")
        .addOptionalInput("sortOrder_view", c => 999)
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addSteps(b => b
            .addStep("createCrd", ResourceManagement, "createKafkaCluster", c =>
                c.register({resourceName: b.inputs.clusterName})
            )
            .addStep("getCrdUid", ResourceManagement, "getResourceUid", c =>
                c.register({
                    resourceName: b.inputs.clusterName,
                    resourceKind: expr.literal("KafkaCluster"),
                })
            )
            // Skip deploy if CRD already Ready (resubmit case — Kafka already running)
            .addStep("deployCluster", SetupKafka, "deployKafkaClusterWithRetry", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    version: b.inputs.version,
                    clusterConfig: expr.jsonPathStrictSerialized(b.inputs.kafkaClusterConfig, "config"),
                    crdName: b.inputs.clusterName,
                    crdUid: c.steps.getCrdUid.outputs.uid,
                }),
                {when: c => ({templateExp: expr.not(expr.equals(c.getCrdUid.outputs.phase, "Ready"))})}
            )
            .addStep("patchReady", ResourceManagement, "patchKafkaClusterReady", c =>
                c.register({resourceName: b.inputs.clusterName}),
                {when: c => ({templateExp: expr.not(expr.equals(c.getCrdUid.outputs.phase, "Ready"))})}
            )
            .addStep("waitForCrdDeletion", ResourceManagement, "waitForCrdDeletion", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: b.inputs.clusterName,
                    resourceKind: expr.literal("KafkaCluster"),
                })
            )
        )
    )


    // ── Section 2: Proxies ───────────────────────────────────────────────

    // Helper: when Kafka is deleted, redeploy proxy in non-capture mode, then wait for CapturedTraffic deletion
    .addTemplate("proxyKafkaGoneTeardown", t => t
        .addRequiredInput("proxyName", typeToken<string>())
        .addRequiredInput("kafkaClusterName", typeToken<string>())
        .addRequiredInput("proxyConfig", typeToken<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>())
        .addRequiredInput("listenPort", typeToken<number>())
        .addRequiredInput("podReplicas", typeToken<number>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole", "CaptureProxy"]))

        .addSteps(b => b
            .addStep("waitForKafkaDeletion", ResourceManagement, "waitForCrdDeletion", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: b.inputs.kafkaClusterName,
                    resourceKind: expr.literal("KafkaCluster"),
                })
            )
            .addStep("redeployNoCapture", SetupCapture, "deployProxyDeployment", c => {
                const config = expr.deserializeRecord(b.inputs.proxyConfig);
                const proxyOpts = expr.get(config, "proxyConfig") as any;
                return c.register({
                    ...selectInputsForRegister(b, c),
                    proxyName: b.inputs.proxyName,
                    listenPort: b.inputs.listenPort,
                    podReplicas: b.inputs.podReplicas,
                    resources: expr.serialize(expr.get(proxyOpts, "resources") as any),
                    jsonConfig: expr.asString(expr.serialize(
                        expr.mergeDicts(
                            expr.omit(proxyOpts, "noCapture", "resources", "internetFacing", "tls", "podReplicas", "listenPort"),
                            expr.makeDict({
                                destinationUri: expr.get(config, "sourceEndpoint"),
                                insecureDestination: expr.get(config, "sourceAllowInsecure"),
                            })
                        ) as any
                    )),
                    kafkaAuthConfigMapName: expr.literal(""),
                    kafkaAuthType: expr.literal(""),
                    kafkaSecretName: expr.literal(""),
                    kafkaCaSecretName: expr.literal(""),
                    crdName: expr.literal(""),
                    crdUid: expr.literal(""),
                });
            })
            .addStep("waitForCapturedTrafficDeletion", ResourceManagement, "waitForCrdDeletion", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: b.inputs.proxyName,
                    resourceKind: expr.literal("CapturedTraffic"),
                })
            )
        )
    )


    .addTemplate("setupSingleProxy", t => t
        .addRequiredInput("proxyConfig", typeToken<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>())
        .addRequiredInput("kafkaClusterName", typeToken<string>())
        .addRequiredInput("kafkaTopicName", typeToken<string>())
        .addRequiredInput("proxyName", typeToken<string>())
        .addRequiredInput("listenPort", typeToken<number>())
        .addRequiredInput("podReplicas", typeToken<number>())
        .addOptionalInput("groupName_view", c => "Proxy")
        .addOptionalInput("sortOrder_view", c => 999)
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole", "CaptureProxy"]))

        .addSteps(b => b
            .addStep("waitForKafkaCluster", ResourceManagement, "waitForKafkaCluster", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: b.inputs.kafkaClusterName,
                    configChecksum: expr.literal(""),
                })
            , {
                when: {
                    templateExp: expr.dig(
                        expr.deserializeRecord(b.inputs.proxyConfig),
                        ["kafkaConfig", "managedByWorkflow"],
                        false
                    )
                }
            })
            .addStep("readKafkaConnectionProfile", ResourceManagement, "readKafkaConnectionProfile", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: b.inputs.kafkaClusterName,
                })
            , {
                when: {
                    templateExp: expr.dig(
                        expr.deserializeRecord(b.inputs.proxyConfig),
                        ["kafkaConfig", "managedByWorkflow"],
                        false
                    )
                }
            })
            .addStep("createCrd", ResourceManagement, "createCapturedTraffic", c =>
                c.register({resourceName: b.inputs.proxyName})
            )
            .addStep("getCrdUid", ResourceManagement, "getResourceUid", c =>
                c.register({
                    resourceName: b.inputs.proxyName,
                    resourceKind: expr.literal("CapturedTraffic"),
                })
            )
            // Skip deploy if CRD already Ready (resubmit case — proxy already running)
            .addStep("setupProxy", SetupCapture, "setupProxy", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    proxyConfig: b.inputs.proxyConfig,
                    kafkaClusterName: b.inputs.kafkaClusterName,
                    kafkaTopicName: b.inputs.kafkaTopicName,
                    proxyName: b.inputs.proxyName,
                    listenPort: b.inputs.listenPort,
                    podReplicas: b.inputs.podReplicas,
                    crdName: b.inputs.proxyName,
                    crdUid: c.steps.getCrdUid.outputs.uid,
                    resolvedKafkaConnection: c.steps.readKafkaConnectionProfile.outputs.bootstrapServers,
                    resolvedKafkaListenerName: c.steps.readKafkaConnectionProfile.outputs.listenerName,
                    resolvedKafkaAuthType: c.steps.readKafkaConnectionProfile.outputs.authType,
                }),
                {when: c => ({templateExp: expr.not(expr.equals(c.getCrdUid.outputs.phase, "Ready"))})}
            )
            .addStep("patchCapturedTraffic", ResourceManagement, "patchCapturedTrafficReady", c =>
                c.register({
                    resourceName: b.inputs.proxyName,
                }),
                {when: c => ({templateExp: expr.not(expr.equals(c.getCrdUid.outputs.phase, "Ready"))})}
            )
            // Dual-mode teardown: Kafka deletion → non-capture mode, CapturedTraffic deletion → full teardown
            .addStepGroup(g => g
                .addStep("kafkaGonePath", INTERNAL, "proxyKafkaGoneTeardown", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        proxyName: b.inputs.proxyName,
                        kafkaClusterName: b.inputs.kafkaClusterName,
                        proxyConfig: b.inputs.proxyConfig,
                        listenPort: b.inputs.listenPort,
                        podReplicas: b.inputs.podReplicas,
                    }),
                    {continueOn: {failed: true}}
                )
                .addStep("directTeardown", ResourceManagement, "waitForCrdDeletion", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        resourceName: b.inputs.proxyName,
                        resourceKind: expr.literal("CapturedTraffic"),
                    }),
                    {continueOn: {failed: true}}
                )
            )
        )
    )


    // ── Section 3: Snapshots ─────────────────────────────────────────────

    .addTemplate("createSingleSnapshot", t => t
        .addRequiredInput("snapshotItemConfig",
            typeToken<z.infer<typeof PER_SOURCE_CREATE_SNAPSHOTS_CONFIG>>())
        .addRequiredInput("sourceConfig",
            typeToken<z.infer<typeof DENORMALIZED_CREATE_SNAPSHOTS_CONFIG>['sourceConfig']>())
        .addOptionalInput("groupName_view", c => "Snapshot")
        .addOptionalInput("sortOrder_view", c => 999)
        .addInputsFromRecord(uniqueRunNonceParam)
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("waitForProxyDeps", ResourceManagement, "waitForCapturedTraffic", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        resourceName: expr.get(c.item, "name"),
                        configChecksum: expr.get(c.item, "configChecksum"),
                        checksumField: expr.literal("configChecksum"),
                    }), {
                    loopWith: makeParameterLoop(
                        expr.get(expr.deserializeRecord(b.inputs.snapshotItemConfig), "dependsOnProxySetups")),
                }
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
                    targetLabel: expr.get(
                        expr.deserializeRecord(b.inputs.snapshotItemConfig), "label"),
                    semaphoreConfigMapName: expr.get(
                        expr.deserializeRecord(b.inputs.snapshotItemConfig), "semaphoreConfigMapName"),
                    semaphoreKey: expr.get(
                        expr.deserializeRecord(b.inputs.snapshotItemConfig), "semaphoreKey"),
                    configChecksum: expr.get(
                        expr.deserializeRecord(b.inputs.snapshotItemConfig), "configChecksum"),
                })
            )
        )
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
                        configChecksum: expr.get(c.item, "configChecksum"),
                    })),
//                    snapshotItemConfig: expr.cast(c.item).to<Serialized<z.infer<typeof PER_SOURCE_CREATE_SNAPSHOTS_CONFIG>>>(),
                    sourceConfig: expr.serialize(
                        expr.get(expr.deserializeRecord(b.inputs.snapshotsSourceConfig), "sourceConfig")
                    )
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
        .addRequiredInput("groupName_view", typeToken<string>())
        .addRequiredInput("crdName", typeToken<string>())
        .addRequiredInput("crdUid", typeToken<string>())
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
                        ...selectInputsForRegister(b, c)
                    });
                },
                {when: {templateExp: expr.not(expr.isEmpty(b.inputs.metadataMigrationConfig))}}
            )
            .addStep("bulkLoadDocuments", DocumentBulkLoad, "setupAndRunBulkLoad", c =>
                    c.register({
                        ...(selectInputsForRegister(b, c)),
                        sessionName: c.steps.idGenerator.id,
                        sourceVersion: b.inputs.sourceVersion,
                        sourceLabel: b.inputs.sourceLabel
                    }),
                {when: {templateExp: expr.not(expr.isEmpty(b.inputs.documentBackfillConfig))}}
            )
        )
    )


    // Wrapper: run migration then self-teardown (used inside parallel group)
    // This ensures selfTeardown happens inside the parallel group, satisfying teardownWatcher.
    .addTemplate("migrateAndSelfTeardown", t => t
        .addRequiredInput("snapshotMigrationConfig", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>())
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("resolvedSnapshotName", typeToken<string>())
        .addRequiredInput("crdName", typeToken<string>())
        .addRequiredInput("crdUid", typeToken<string>())
        .addInputsFromRecord(uniqueRunNonceParam)
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("migrateFromSnapshot", INTERNAL, "migrateFromSnapshot", c => {
                    const snapshotMigrationConfig = expr.deserializeRecord(b.inputs.snapshotMigrationConfig);
                    const snapshotRepoConfig = expr.get(snapshotMigrationConfig, "snapshotConfig");

                    return c.register({
                        ...selectInputsForRegister(b, c),
                        ...selectInputsFieldsAsExpressionRecord(c.item, c,
                            getZodKeys(PER_INDICES_SNAPSHOT_MIGRATION_CONFIG)),
                        sourceVersion: expr.get(snapshotMigrationConfig, "sourceVersion"),
                        sourceLabel: expr.get(snapshotMigrationConfig, "sourceLabel"),
                        targetConfig: expr.serialize(expr.get(snapshotMigrationConfig, "targetConfig")),
                        snapshotConfig: expr.serialize(expr.makeDict({
                            snapshotName: b.inputs.resolvedSnapshotName,
                            label: expr.get(snapshotRepoConfig, "label"),
                            repoConfig: expr.get(snapshotRepoConfig, "repoConfig")
                        })),
                        migrationLabel: expr.get(c.item, "label"),
                        groupName_view: expr.get(c.item, "label"),
                        sourceEndpoint: expr.dig(snapshotMigrationConfig, ["sourceEndpoint"], "")
                    });
                }, {
                    loopWith: makeParameterLoop(
                        expr.get(expr.deserializeRecord(b.inputs.snapshotMigrationConfig), "migrations"))
                }
            )
            .addStep("patchSnapshotMigration", ResourceManagement, "patchSnapshotMigrationReady", c =>
                c.register({
                    resourceName: b.inputs.resourceName,
                    configChecksum: expr.literal(""),
                    checksumForReplayer: expr.literal(""),
                })
            )
            .addStep("selfTeardown", ResourceManagement, "deleteCrd", c =>
                c.register({
                    resourceName: b.inputs.resourceName,
                    resourceKind: expr.literal("SnapshotMigration"),
                })
            )
        )
    )


    .addTemplate("runSingleSnapshotMigration", t => t
        .addRequiredInput("snapshotMigrationConfig", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>())
        .addRequiredInput("resourceName", typeToken<string>())
        .addOptionalInput("groupName_view", c => "Snapshot Migration")
        .addOptionalInput("sortOrder_view", c => 999)
        .addInputsFromRecord(uniqueRunNonceParam)
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("createCrd", ResourceManagement, "createSnapshotMigration", c =>
                c.register({resourceName: b.inputs.resourceName})
            )
            .addStep("getCrdUid", ResourceManagement, "getResourceUid", c =>
                c.register({
                    resourceName: b.inputs.resourceName,
                    resourceKind: expr.literal("SnapshotMigration"),
                })
            )
            .addStep("waitForSnapshot", ResourceManagement, "waitForDataSnapshot", c => {
                const snapshotNameRes = expr.get(
                    expr.deserializeRecord(b.inputs.snapshotMigrationConfig),
                    "snapshotNameResolution");
                return c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: expr.ternary(
                        expr.hasKey(snapshotNameRes, "dataSnapshotResourceName"),
                        expr.getLoose(snapshotNameRes, "dataSnapshotResourceName"),
                        expr.literal("")),
                    configChecksum: expr.literal(""),
                    checksumField: expr.literal("checksumForSnapshotMigration"),
                });
            }, {
                    when: {
                        templateExp: expr.hasKey(
                            expr.get(
                                expr.deserializeRecord(b.inputs.snapshotMigrationConfig),
                                "snapshotNameResolution"
                            ),
                            "dataSnapshotResourceName")
                    }
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
                    when: {
                        templateExp: expr.hasKey(
                            expr.get(
                                expr.deserializeRecord(b.inputs.snapshotMigrationConfig),
                                "snapshotNameResolution"
                            ),
                            "dataSnapshotResourceName")
                    }
                }
            )
            // Parallel: CRD deletion watcher + (migrate → patchReady → self-delete CRD)
            // Natural completion: migration finishes, patches Ready, deletes CRD → watcher resolves
            // External reset: CLI deletes CRD → k8s cascades to coordinator/RFS → watcher resolves
            .addStepGroup(g => g
                .addStep("deletionWatcher", ResourceManagement, "waitForCrdDeletion", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        resourceName: b.inputs.resourceName,
                        resourceKind: expr.literal("SnapshotMigration"),
                    })
                )
                .addStep("migrateAndTeardown", INTERNAL, "migrateAndSelfTeardown", c => {
                        const snapshotMigrationConfig = expr.deserializeRecord(b.inputs.snapshotMigrationConfig);
                        const snapshotNameResolution = expr.get(snapshotMigrationConfig, "snapshotNameResolution");
                        return c.register({
                            ...selectInputsForRegister(b, c),
                            snapshotMigrationConfig: b.inputs.snapshotMigrationConfig,
                            resourceName: b.inputs.resourceName,
                            resolvedSnapshotName: expr.ternary(
                                expr.hasKey(snapshotNameResolution, "externalSnapshotName"),
                                expr.getLoose(snapshotNameResolution, "externalSnapshotName"),
                                c.steps.readSnapshotName.outputs.snapshotName
                            ),
                            crdName: b.inputs.resourceName,
                            crdUid: c.steps.getCrdUid.outputs.uid,
                        });
                    },
                    {continueOn: {failed: true}}
                )
            )
        )
    )


    // ── Section 5: Traffic Replays ───────────────────────────────────────

    .addTemplate("runSingleReplay", t => t
        .addRequiredInput("kafkaConfig", typeToken<z.infer<typeof NAMED_KAFKA_CLIENT_CONFIG>>())
        .addRequiredInput("kafkaClusterName", typeToken<string>())
        .addRequiredInput("fromProxy", typeToken<string>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("replayerOptions", typeToken<z.infer<typeof ARGO_REPLAYER_OPTIONS>>())
        .addOptionalInput("groupName_view", c => "Traffic Replay")
        .addOptionalInput("sortOrder_view", c => 999)
        .addRequiredInput("dependsOnSnapshotMigrations", typeToken<z.infer<typeof ENRICHED_SNAPSHOT_MIGRATION_FILTER>[]>())

        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole", "TrafficReplayer"]))

        .addSteps(b => {
            const replayerName = expr.concat(
                b.inputs.fromProxy,
                expr.literal("-"),
                expr.get(expr.deserializeRecord(b.inputs.targetConfig), "label"),
                expr.literal("-replayer"));

            return b
                .addStep("waitForSnapshotMigrationDeps", ResourceManagement, "waitForSnapshotMigration", c => {
                        return c.register({
                            ...selectInputsForRegister(b, c),
                            resourceName: expr.concat(
                                expr.asString(expr.get(c.item, "source")),
                                expr.literal("-"),
                                expr.asString(expr.get(c.item, "snapshot"))
                            ),
                            configChecksum: expr.asString(expr.get(c.item, "configChecksum")),
                            checksumField: expr.literal("checksumForReplayer"),
                        });
                    }, {
                        loopWith: makeParameterLoop(expr.deserializeRecord(b.inputs.dependsOnSnapshotMigrations)),
                        when: {
                            templateExp: expr.not(
                                expr.isEmpty(expr.deserializeRecord(b.inputs.dependsOnSnapshotMigrations)))
                        }
                    }
                )
                .addStep("waitForProxy", ResourceManagement, "waitForCapturedTraffic", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        resourceName: b.inputs.fromProxy,
                        configChecksum: expr.literal(""),
                        checksumField: expr.literal("configChecksum"),
                    })
                )
                .addStep("waitForKafkaCluster", ResourceManagement, "waitForKafkaCluster", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        resourceName: b.inputs.kafkaClusterName,
                        configChecksum: expr.literal(""),
                    })
                , {
                    when: {
                        templateExp: expr.dig(
                            expr.deserializeRecord(b.inputs.kafkaConfig),
                            ["managedByWorkflow"],
                            false
                        )
                    }
                })
                .addStep("readKafkaConnectionProfile", ResourceManagement, "readKafkaConnectionProfile", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        resourceName: b.inputs.kafkaClusterName
                    })
                , {
                    when: {
                        templateExp: expr.dig(
                            expr.deserializeRecord(b.inputs.kafkaConfig),
                            ["managedByWorkflow"],
                            false
                        )
                    }
                })
                // Create TrafficReplay CRD and read its UID
                .addStep("createTrafficReplay", ResourceManagement, "createTrafficReplay", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        resourceName: replayerName,
                    })
                )
                .addStep("getCrdUid", ResourceManagement, "getResourceUid", c =>
                    c.register({
                        resourceName: replayerName,
                        resourceKind: expr.literal("TrafficReplay"),
                    })
                )
                .addStep("deployReplayer", Replayer, "setupReplayer", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        kafkaGroupId: expr.concat(expr.literal("replayer-"),
                            expr.get(expr.deserializeRecord(b.inputs.targetConfig), "label")),
                        name: replayerName,
                        crdName: replayerName,
                        crdUid: c.steps.getCrdUid.outputs.uid,
                        resolvedKafkaConnection: c.steps.readKafkaConnectionProfile.outputs.bootstrapServers,
                        resolvedKafkaListenerName: c.steps.readKafkaConnectionProfile.outputs.listenerName,
                        resolvedKafkaAuthType: c.steps.readKafkaConnectionProfile.outputs.authType,
                    })
                )
                .addStep("patchTrafficReplayReady", ResourceManagement, "patchTrafficReplayReady", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        resourceName: replayerName,
                    })
                )
                .addStep("waitForCrdDeletion", ResourceManagement, "waitForCrdDeletion", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        resourceName: replayerName,
                        resourceKind: expr.literal("TrafficReplay"),
                    })
                );
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
            .addStep("createKafka", INTERNAL, "setupSingleKafkaCluster", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    kafkaClusterConfig: expr.serialize(expr.makeDict({
                        name: expr.get(c.item, "name"),
                        version: expr.get(c.item, "version"),
                        config: expr.deserializeRecord(expr.get(c.item, "config")),
                        topics: expr.deserializeRecord(expr.get(c.item, "topics")),
                        configChecksum: expr.get(c.item, "configChecksum"),
                    })),
                    //kafkaClusterConfig: expr.cast(c.item).to<Serialized<z.infer<typeof NAMED_KAFKA_CLUSTER_CONFIG>>>(),
                    clusterName: expr.get(c.item, "name"),
                    version: expr.cast(expr.get(c.item, "version")).to<string>(),
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
                        kafkaConfig: expr.deserializeRecord(expr.get(c.item, "kafkaConfig")),
                        sourceEndpoint: expr.get(c.item, "sourceEndpoint"),
                        sourceAllowInsecure: expr.get(c.item, "sourceAllowInsecure"),
                        proxyConfig: expr.deserializeRecord(expr.get(c.item, "proxyConfig")),
                        configChecksum: expr.get(c.item, "configChecksum"),
                        checksumForSnapshot: expr.get(c.item, "checksumForSnapshot"),
                        checksumForReplayer: expr.get(c.item, "checksumForReplayer"),
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
                        // TODO/BUG - need to fix this in the type system.
                        // The following line is required, but won't type check w/out the any cast!
                        expr.dig(
                            expr.deserializeRecord(expr.get(c.item, "targetConfig")),
                            ["label"],
                            ""
                        ),
                        // expr.get(expr.get(c.item, "targetConfig"), "label"),
                        expr.literal("-"),
                        expr.get(c.item, "label")
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
                    snapshotMigrationConfig: expr.serialize(expr.makeDict({
                        label: expr.get(c.item, "label"),
                        snapshotNameResolution: expr.deserializeRecord(expr.get(c.item, "snapshotNameResolution")),
                        snapshotConfigChecksum: expr.get(c.item, "snapshotConfigChecksum"),
                        migrations: expr.deserializeRecord(expr.get(c.item, "migrations")),
                        sourceVersion: expr.get(c.item, "sourceVersion"),
                        sourceLabel: expr.get(c.item, "sourceLabel"),
                        targetConfig: expr.deserializeRecord(expr.get(c.item, "targetConfig")),
                        snapshotConfig: expr.deserializeRecord(expr.get(c.item, "snapshotConfig")),
                        sourceEndpoint: expr.dig(c.item, ["sourceEndpoint"], ""),
                        configChecksum: expr.get(c.item, "configChecksum"),
                        checksumForReplayer: expr.get(c.item, "checksumForReplayer"),
                    })),
//                    snapshotMigrationConfig: expr.cast(c.item).to<Serialized<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>>()
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
    .getFullScope();
