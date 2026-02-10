import {z} from 'zod';
import {
    ARGO_CREATE_SNAPSHOT_OPTIONS,
    ARGO_MIGRATION_CONFIG,
    COMPLETE_SNAPSHOT_CONFIG,
    DEFAULT_RESOURCES,
    DENORMALIZED_CREATE_SNAPSHOTS_CONFIG,
    DENORMALIZED_PROXY_CONFIG,
    DENORMALIZED_REPLAY_CONFIG,
    getZodKeys,
    METADATA_OPTIONS,
    NAMED_KAFKA_CLUSTER_CONFIG,
    NAMED_SOURCE_CLUSTER_CONFIG,
    NAMED_SOURCE_CLUSTER_CONFIG_WITHOUT_SNAPSHOT_INFO,
    NAMED_TARGET_CLUSTER_CONFIG,
    PER_INDICES_SNAPSHOT_MIGRATION_CONFIG,
    PER_SOURCE_CREATE_SNAPSHOTS_CONFIG,
    RFS_OPTIONS,
    SNAPSHOT_MIGRATION_CONFIG,
    SNAPSHOT_MIGRATION_FILTER,
    TARGET_CLUSTER_CONFIG,
} from '@opensearch-migrations/schemas'
import {ConfigManagementHelpers} from "./configManagementHelpers";
import {
    AllowLiteralOrExpression,
    configMapKey,
    defineParam,
    defineRequiredParam,
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

import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {ImageParameters, LogicalOciImages, makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {SetupKafka} from "./setupKafka";

const uniqueRunNonceParam = {
    uniqueRunNonce: defineRequiredParam<string>({description: "Workflow session nonce"})
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

const CRD_API_VERSION = "migration.opensearch.org/v1alpha1";

export const FullMigration = WorkflowBuilder.create({
    k8sResourceName: "full-migration",
    parallelism: 100,
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    // ── Utility ──────────────────────────────────────────────────────────

    .addTemplate("doNothing", t => t
        .addSteps(b => b.addStepGroup(c => c)))


    .addTemplate("signalReplayerForTarget", t => t
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["sh", "-c"])
            .addResources(DEFAULT_RESOURCES.MIGRATION_CONSOLE_CLI)
            .addArgs(["echo signal replayer here"])))


    // ── Wait templates (resource get with retry) ─────────────────────────

    .addTemplate("waitForKafkaTopic", t => t
        .addRequiredInput("topicName", typeToken<string>())
        .addWaitForResource(b => b
            .setDefinition({
                action: "get",
                successCondition: "status.topicName",
                manifest: {
                    apiVersion: "kafka.strimzi.io/v1beta2",
                    kind: "KafkaTopic",
                    metadata: { name: b.inputs.topicName }
                }
            }))
        .addRetryParameters({
            limit: "60", retryPolicy: "Always",
            backoff: { duration: "10", factor: "2", cap: "120" }
        })
    )


    .addTemplate("waitForCapturedTraffic", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addWaitForResource(b => b
            .setDefinition({
                action: "get",
                successCondition: "status.phase == Ready",
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: "CapturedTraffic",
                    metadata: { name: b.inputs.resourceName }
                }
            }))
        .addRetryParameters({
            limit: "120", retryPolicy: "Always",
            backoff: { duration: "10", factor: "2", cap: "120" }
        })
    )


    .addTemplate("waitForDataSnapshot", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addWaitForResource(b => b
            .setDefinition({
                action: "get",
                successCondition: "status.phase == Ready",
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: "DataSnapshot",
                    metadata: { name: b.inputs.resourceName }
                }
            }))
        .addRetryParameters({
            limit: "120", retryPolicy: "Always",
            backoff: { duration: "10", factor: "2", cap: "120" }
        })
    )


    .addTemplate("waitForSnapshotMigration", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addWaitForResource(b => b
            .setDefinition({
                action: "get",
                successCondition: "status.phase == Ready",
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: "SnapshotMigration",
                    metadata: { name: b.inputs.resourceName }
                }
            })
            .setWaitForCreation({
                kubectlImage: b.inputs.imageMigrationConsoleLocation,
                kubectlImagePullPolicy: b.inputs.imageMigrationConsolePullPolicy,
                maxDuration: (2*24*60*60)
            })
        )
        .addRetryParameters({
            limit: "120", retryPolicy: "Always",
            backoff: { duration: "10", factor: "2", cap: "120" }
        })
    )


    // ── CRD create templates ─────────────────────────────────────────────

    .addTemplate("createCapturedTrafficResource", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "create",
                setOwnerReference: false,
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: "CapturedTraffic",
                    metadata: { name: b.inputs.resourceName },
                    spec: {}
                }
            }))
    )


    .addTemplate("createDataSnapshotResource", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "create",
                setOwnerReference: false,
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: "DataSnapshot",
                    metadata: { name: b.inputs.resourceName },
                    spec: {}
                }
            }))
    )


    .addTemplate("createSnapshotMigrationResource", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "create",
                setOwnerReference: false,
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: "SnapshotMigration",
                    metadata: { name: b.inputs.resourceName },
                    spec: {}
                }
            }))
    )


    // ── CRD patch-to-ready templates ─────────────────────────────────────

    .addTemplate("patchCapturedTrafficReady", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "patch",
                flags: ["--type", "merge"],
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: "CapturedTraffic",
                    metadata: { name: b.inputs.resourceName },
                    status: { phase: "Ready" }
                }
            }))
    )


    .addTemplate("patchDataSnapshotReady", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "patch",
                flags: ["--type", "merge"],
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: "DataSnapshot",
                    metadata: { name: b.inputs.resourceName },
                    status: { phase: "Ready" }
                }
            }))
    )


    .addTemplate("patchSnapshotMigrationReady", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "patch",
                flags: ["--type", "merge"],
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: "SnapshotMigration",
                    metadata: { name: b.inputs.resourceName },
                    status: { phase: "Ready" }
                }
            }))
    )


    // ── Section 1: Kafka Clusters ────────────────────────────────────────

    .addTemplate("setupSingleKafkaCluster", t => t
        .addRequiredInput("kafkaClusterConfig", typeToken<z.infer<typeof NAMED_KAFKA_CLUSTER_CONFIG>>())

        .addSteps(b => b
            .addStep("deployCluster", SetupKafka, "deployKafkaCluster", c =>
                c.register({
                    clusterName: expr.get(
                        expr.deserializeRecord(b.inputs.kafkaClusterConfig), "name")
                })
            )
            .addStep("createTopics", SetupKafka, "createKafkaTopic", c =>
                c.register({
                    clusterName: expr.get(
                        expr.deserializeRecord(b.inputs.kafkaClusterConfig), "name"),
                    topicName: expr.asString(c.item),
                    topicPartitions: 1,
                    topicReplicas: 1
                }), {
                    loopWith: makeParameterLoop(
                        expr.get(expr.deserializeRecord(b.inputs.kafkaClusterConfig), "topics"))
                }
            )
        )
    )


    // ── Section 2: Proxies ───────────────────────────────────────────────

    .addTemplate("setupSingleProxy", t => t
        .addRequiredInput("proxyConfig", typeToken<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>())

        .addDag(b => b
            .addTask("waitForTopic", INTERNAL, "waitForKafkaTopic", c =>
                c.register({
                    topicName: expr.jsonPathStrict(b.inputs.proxyConfig, "kafkaConfig", "kafkaTopic")
                })
            )
            .addTask("placeholderProxySetup", INTERNAL, "doNothing",
                { dependencies: ["waitForTopic"] as const }
            )
            .addTask("createCapturedTraffic", INTERNAL, "createCapturedTrafficResource", c =>
                c.register({
                    resourceName: expr.get(
                        expr.deserializeRecord(b.inputs.proxyConfig), "name")
                }),
                { dependencies: ["placeholderProxySetup"] as const }
            )
            .addTask("patchCapturedTraffic", INTERNAL, "patchCapturedTrafficReady", c =>
                c.register({
                    resourceName: expr.get(
                        expr.deserializeRecord(b.inputs.proxyConfig), "name")
                }),
                { dependencies: ["createCapturedTraffic"] as const }
            )
        )
    )


    // ── Section 3: Snapshots ─────────────────────────────────────────────

    .addTemplate("createSingleSnapshot", t => t
        .addRequiredInput("snapshotItemConfig",
            typeToken<z.infer<typeof PER_SOURCE_CREATE_SNAPSHOTS_CONFIG>>())
        .addRequiredInput("sourceConfig",
            typeToken<z.infer<typeof DENORMALIZED_CREATE_SNAPSHOTS_CONFIG>['sourceConfig']>())
        .addInputsFromRecord(uniqueRunNonceParam)
        .addInputsFromRecord(ImageParameters)

        .addDag(b => b
            .addTask("waitForProxyDeps", INTERNAL, "waitForCapturedTraffic", c =>
                    c.register({
                        resourceName: expr.asString(c.item)
                    }), {
                    loopWith: makeParameterLoop(
                        expr.ternary(
                            expr.hasKey(expr.deserializeRecord(b.inputs.snapshotItemConfig),
                                "dependsUponProxySetups"),
                            expr.getLoose(expr.deserializeRecord(b.inputs.snapshotItemConfig),
                                "dependsUponProxySetups"),
                            expr.literal([]))),
                    when: { templateExp: expr.not(expr.isEmpty(
                        expr.getLoose(expr.deserializeRecord(b.inputs.snapshotItemConfig),
                            "dependsUponProxySetups")))
                    }
                }
            )
            .addTask("createOrGetSnapshot", CreateOrGetSnapshot, "createOrGetSnapshot", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    sourceConfig: b.inputs.sourceConfig,
                    createSnapshotConfig: expr.serialize(
                        expr.get(expr.deserializeRecord(b.inputs.snapshotItemConfig), "config")),
                    snapshotPrefix: expr.get(
                        expr.deserializeRecord(b.inputs.snapshotItemConfig), "snapshotPrefix"),
                    snapshotConfig: expr.serialize(expr.makeDict({
                        config: expr.makeDict({
                            createSnapshotConfig: expr.get(
                                expr.deserializeRecord(b.inputs.snapshotItemConfig), "config")
                        }),
                        repoConfig: expr.get(
                            expr.deserializeRecord(b.inputs.snapshotItemConfig), "repo"),
                        // TODO: label should be the logical snapshot name (record key from user config),
                        // not snapshotPrefix. PER_SOURCE_CREATE_SNAPSHOTS_CONFIG needs a label field.
                        label: expr.get(
                            expr.deserializeRecord(b.inputs.snapshotItemConfig), "snapshotPrefix")
                    })),
                    targetLabel: expr.get(
                        expr.deserializeRecord(b.inputs.snapshotItemConfig), "snapshotPrefix"),
                    semaphoreConfigMapName: expr.get(
                        expr.deserializeRecord(b.inputs.snapshotItemConfig), "semaphoreConfigMapName"),
                    semaphoreKey: expr.get(
                        expr.deserializeRecord(b.inputs.snapshotItemConfig), "semaphoreKey")
                }),
                { dependencies: ["waitForProxyDeps"] as const }
            )
            .addTask("createDataSnapshot", INTERNAL, "createDataSnapshotResource", c =>
                c.register({
                    resourceName: expr.get(
                        expr.deserializeRecord(b.inputs.snapshotItemConfig), "snapshotPrefix")
                }),
                { dependencies: ["createOrGetSnapshot"] as const }
            )
            .addTask("patchDataSnapshot", INTERNAL, "patchDataSnapshotReady", c =>
                c.register({
                    resourceName: expr.get(
                        expr.deserializeRecord(b.inputs.snapshotItemConfig), "snapshotPrefix")
                }),
                { dependencies: ["createDataSnapshot"] as const }
            )
        )
    )


    .addTemplate("createSnapshotsForSource", t => t
        .addRequiredInput("snapshotsSourceConfig",
            typeToken<z.infer<typeof DENORMALIZED_CREATE_SNAPSHOTS_CONFIG>>())
        .addInputsFromRecord(uniqueRunNonceParam)
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("createSnapshot", INTERNAL, "createSingleSnapshot", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    snapshotItemConfig: expr.serialize(c.item),
                    sourceConfig: expr.jsonPathStrictSerialized(b.inputs.snapshotsSourceConfig, "sourceConfig")
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
        .addRequiredInput("groupName", typeToken<string>())
        .addOptionalInput("metadataMigrationConfig", c =>
            expr.empty<z.infer<typeof METADATA_OPTIONS>>())
        .addOptionalInput("documentBackfillConfig", c =>
            expr.empty<z.infer<typeof RFS_OPTIONS>>())

        .addInputsFromRecord(uniqueRunNonceParam)
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("idGenerator", INTERNAL, "doNothing")
            .addStep("metadataMigrate", MetadataMigration, "migrateMetaData", c => {
                    return c.register({
                        ...selectInputsForRegister(b, c)
                    });
                },
                { when: { templateExp: expr.not(expr.isEmpty(b.inputs.metadataMigrationConfig)) }}
            )
            .addStep("bulkLoadDocuments", DocumentBulkLoad, "setupAndRunBulkLoad", c =>
                    c.register({
                        ...(selectInputsForRegister(b, c)),
                        sessionName: c.steps.idGenerator.id,
                        sourceVersion: b.inputs.sourceVersion,
                        sourceLabel: b.inputs.sourceLabel
                    }),
                { when: { templateExp: expr.not(expr.isEmpty(b.inputs.documentBackfillConfig)) }}
            )
            .addStep("targetBackfillCompleteCheck", ConfigManagementHelpers, "decrementLatch", c =>
                c.register({
                    ...(selectInputsForRegister(b, c)),
                    prefix: b.inputs.uniqueRunNonce,
                    targetName: expr.jsonPathStrict(b.inputs.targetConfig, "label"),
                    processorId: c.steps.idGenerator.id
                }))
            .addStep("signalReplayerForTarget", INTERNAL, "signalReplayerForTarget", c =>
                c.register({
                    ...selectInputsForRegister(b, c)
                }))
        )
    )


    .addTemplate("runSingleSnapshotMigration", t => t
        .addRequiredInput("snapshotMigrationConfig",
            typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>())
        .addInputsFromRecord(uniqueRunNonceParam)
        .addInputsFromRecord(ImageParameters)

        .addDag(b => b
            .addTask("waitForSnapshot", INTERNAL, "waitForDataSnapshot", c =>
                c.register({
                    resourceName: expr.jsonPathStrict(b.inputs.snapshotMigrationConfig, "snapshotLabel")
                })
            )
            .addTask("migrateFromSnapshot", INTERNAL, "migrateFromSnapshot", c => {
                    return c.register({
                        ...selectInputsForRegister(b, c),
                        ...selectInputsFieldsAsExpressionRecord(c.item, c,
                            getZodKeys(PER_INDICES_SNAPSHOT_MIGRATION_CONFIG)),
                        sourceVersion: expr.jsonPathStrict(b.inputs.snapshotMigrationConfig, "sourceVersion"),
                        sourceLabel: expr.jsonPathStrict(b.inputs.snapshotMigrationConfig, "sourceLabel"),
                        targetConfig: expr.jsonPathStrictSerialized(b.inputs.snapshotMigrationConfig, "targetConfig"),
                        snapshotConfig: expr.jsonPathStrictSerialized(b.inputs.snapshotMigrationConfig, "snapshotConfig"),
                        migrationLabel: expr.get(c.item, "label"),
                        groupName: expr.get(c.item, "label")
                    });
                }, {
                    loopWith: makeParameterLoop(
                        expr.get(expr.deserializeRecord(b.inputs.snapshotMigrationConfig), "migrations")),
                    dependencies: ["waitForSnapshot"] as const
                }
            )
            .addTask("createSnapshotMigration", INTERNAL, "createSnapshotMigrationResource", c =>
                c.register({
                    resourceName: expr.jsonPathStrict(b.inputs.snapshotMigrationConfig, "label")
                }),
                { dependencies: ["migrateFromSnapshot"] as const }
            )
            .addTask("patchSnapshotMigration", INTERNAL, "patchSnapshotMigrationReady", c =>
                c.register({
                    resourceName: expr.jsonPathStrict(b.inputs.snapshotMigrationConfig, "label")
                }),
                { dependencies: ["createSnapshotMigration"] as const }
            )
        )
    )


    // ── Section 5: Traffic Replays ───────────────────────────────────────

    .addTemplate("runSingleReplay", t => t
        .addRequiredInput("replayConfig", typeToken<z.infer<typeof DENORMALIZED_REPLAY_CONFIG>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addDag(b => b
            .addTask("waitForSnapshotMigrationDeps", INTERNAL, "waitForSnapshotMigration", c => {
                    return c.register({
                        ...selectInputsForRegister(b, c),
                        resourceName: expr.concat(
                            expr.asString(expr.get(c.item, "source")),
                            expr.literal("-"),
                            expr.asString(expr.get(c.item, "snapshot"))
                        )
                    });
                }, {
                    loopWith: makeParameterLoop(
                        expr.dig(expr.deserializeRecord(b.inputs.replayConfig), ["dependsOnSnapshotMigrations"], [])),
                    when: { templateExp: expr.not(expr.isEmpty(
                        expr.getLoose(expr.deserializeRecord(b.inputs.replayConfig),
                            "dependsOnSnapshotMigrations")))
                    }
                }
            )
            .addTask("placeholderReplay", INTERNAL, "doNothing",
                { dependencies: ["waitForSnapshotMigrationDeps"] as const }
            )
        )
    )


    // ── Main ─────────────────────────────────────────────────────────────

    .addTemplate("main", t => t
        .addRequiredInput("config",
            typeToken<z.infer<typeof ARGO_MIGRATION_CONFIG>>(),
            "Full migration configuration")
        .addRequiredInput("uniqueRunNonce", typeToken<string>())
        .addInputsFromRecord(defaultImagesMap(t.inputs.workflowParameters.imageConfigMapName))

        .addDag(b => b
            .addTask("setupKafkaClusters", INTERNAL, "setupSingleKafkaCluster", c =>
                c.register({
                    kafkaClusterConfig: expr.serialize(c.item)
                }), {
                    loopWith: makeParameterLoop(
                        expr.ternary(
                            expr.hasKey(expr.deserializeRecord(b.inputs.config), "kafkaClusters"),
                            expr.getLoose(expr.deserializeRecord(b.inputs.config), "kafkaClusters"),
                            expr.literal([])
                        )),
                    when: { templateExp: expr.hasKey(expr.deserializeRecord(b.inputs.config), "kafkaClusters") }
                }
            )
            .addTask("setupProxies", INTERNAL, "setupSingleProxy", c =>
                c.register({
                    proxyConfig: expr.serialize(c.item)
                }), {
                    loopWith: makeParameterLoop(
                        expr.get(expr.deserializeRecord(b.inputs.config), "proxies"))
                }
            )
            .addTask("createSnapshots", INTERNAL, "createSnapshotsForSource", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    snapshotsSourceConfig: expr.serialize(c.item)
                }), {
                    loopWith: makeParameterLoop(
                        expr.get(expr.deserializeRecord(b.inputs.config), "snapshots"))
                }
            )
            .addTask("runSnapshotMigrations", INTERNAL, "runSingleSnapshotMigration", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    snapshotMigrationConfig: expr.serialize(c.item)
                }), {
                    loopWith: makeParameterLoop(
                        expr.get(expr.deserializeRecord(b.inputs.config), "snapshotMigrations"))
                }
            )
            .addTask("runTrafficReplays", INTERNAL, "runSingleReplay", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    replayConfig: expr.serialize(c.item)
                }), {
                    loopWith: makeParameterLoop(
                        expr.get(expr.deserializeRecord(b.inputs.config), "trafficReplays"))
                }
            )
        )
    )


    .setEntrypoint("main")
    .getFullScope();
