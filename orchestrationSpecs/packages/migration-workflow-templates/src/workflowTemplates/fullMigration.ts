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
    SNAPSHOT_NAME_RESOLUTION,
    SNAPSHOT_REPO_CONFIG,
    TARGET_CLUSTER_CONFIG,
} from '@opensearch-migrations/schemas'
import {
    AllowLiteralOrExpression,
    configMapKey,
    defineParam,
    defineRequiredParam,
    expr, GenericScope,
    IMAGE_PULL_POLICY,
    InputParamDef, InputParametersRecord,
    INTERNAL, isExpression,
    makeParameterLoop, OutputParametersRecord,
    selectInputsFieldsAsExpressionRecord,
    selectInputsForRegister, TemplateBuilder,
    typeToken, WorkflowAndTemplatesScope,
    WorkflowBuilder
} from '@opensearch-migrations/argo-workflow-builders';
import {DocumentBulkLoad} from "./documentBulkLoad";
import {MetadataMigration} from "./metadataMigration";
import {CreateOrGetSnapshot} from "./createOrGetSnapshot";

import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {ImageParameters, LogicalOciImages, makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {SetupKafka} from "./setupKafka";

const SECONDS_IN_DAYS = 24*3600;
const LONGEST_POSSIBLE_MIGRATION = 365*SECONDS_IN_DAYS;

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


    .addTemplate("signalReplayerForTarget", t => t
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["sh", "-c"])
            .addResources(DEFAULT_RESOURCES.MIGRATION_CONSOLE_CLI)
            .addArgs(["echo signal replayer here"])))


    // ── Wait templates (resource get with retry) ─────────────────────────

    .addTemplate("waitForKafkaTopic", b => b
        .addRequiredInput("resourceName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addWaitForNewResource(b=>b
            .setDefinition({
                resourceKindAndName: expr.concat(expr.literal(""), b.inputs.resourceName),
                waitForCreation: {
                    kubectlImage: b.inputs.imageMigrationConsoleLocation,
                    kubectlImagePullPolicy: b.inputs.imageMigrationConsolePullPolicy,
                    maxDurationSeconds: LONGEST_POSSIBLE_MIGRATION
                }
            })
        )
    )


    .addTemplate("waitForCapturedTraffic", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addWaitForExistingResource(b=>b
            .setDefinition({
                resource: {
                    apiVersion: CRD_API_VERSION,
                    kind: "CapturedTraffic",
                    name: b.inputs.resourceName
                },
                conditions: {successCondition: "status.phase == Ready"}
            })
        )
    )


    .addTemplate("waitForDataSnapshot", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addWaitForExistingResource(b=>b
            .setDefinition({
                resource: {
                    apiVersion: CRD_API_VERSION,
                    kind:"DataSnapshot",
                    name: b.inputs.resourceName
                },
                conditions: {successCondition: "status.phase == Ready"}
            })
        )
    )


    .addTemplate("waitForSnapshotMigration", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addWaitForExistingResource(b=>b
            .setDefinition({
                resource: {
                    apiVersion: CRD_API_VERSION,
                    kind: "SnapshotMigration",
                    name: b.inputs.resourceName
                },
                conditions: {successCondition: "status.phase == Ready"}
            })
        )
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
        .addRequiredInput("snapshotName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "patch",
                flags: ["--type", "merge"],
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: "DataSnapshot",
                    metadata: { name: b.inputs.resourceName },
                    status: { phase: "Ready", snapshotName: b.inputs.snapshotName }
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


    .addTemplate("readDataSnapshotName", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "get",
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: "DataSnapshot",
                    metadata: { name: b.inputs.resourceName }
                }
            })
            .addJsonPathOutput("snapshotName", "{.status.snapshotName}", typeToken<string>()))
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
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addDag(b => b
            .addTask("waitForTopic", INTERNAL, "waitForKafkaTopic", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: expr.jsonPathStrict(b.inputs.proxyConfig, "kafkaConfig", "kafkaTopic")
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
                        ...selectInputsForRegister(b, c),
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
                    createSnapshotConfig: expr.jsonPathStrictSerialized(b.inputs.snapshotItemConfig, "config"),
                    snapshotPrefix: expr.get(
                        expr.deserializeRecord(b.inputs.snapshotItemConfig), "snapshotPrefix"),
                    snapshotConfig: expr.serialize(expr.makeDict({
                        config: expr.makeDict({
                            createSnapshotConfig: expr.get(
                                expr.deserializeRecord(b.inputs.snapshotItemConfig), "config")
                        }),
                        repoConfig: expr.deserializeRecord(expr.jsonPathStrictSerialized(b.inputs.snapshotItemConfig, "repo")),
                        label: expr.get(
                            expr.deserializeRecord(b.inputs.snapshotItemConfig), "label")
                    })),
                    targetLabel: expr.get(
                        expr.deserializeRecord(b.inputs.snapshotItemConfig), "label"),
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
                        expr.deserializeRecord(b.inputs.snapshotItemConfig), "label")
                }),
                { dependencies: ["createOrGetSnapshot"] as const }
            )
            .addTask("patchDataSnapshot", INTERNAL, "patchDataSnapshotReady", c =>
                c.register({
                    resourceName: expr.get(
                        expr.deserializeRecord(b.inputs.snapshotItemConfig), "label"),
                    snapshotName: expr.get(
                        expr.deserializeRecord(c.tasks.createOrGetSnapshot.outputs.snapshotConfig),
                        "snapshotName")
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
                    ...selectInputsForRegister(b, c),
                    resourceName: expr.getLoose(
                        expr.deserializeRecord(
                            expr.jsonPathStrictSerialized(b.inputs.snapshotMigrationConfig, "snapshotNameResolution")),
                        "dataSnapshotResourceName")
                }), {
                    when: { templateExp: expr.hasKey(
                        expr.deserializeRecord(
                            expr.jsonPathStrictSerialized(b.inputs.snapshotMigrationConfig, "snapshotNameResolution")),
                        "dataSnapshotResourceName") }
                }
            )
            .addTask("readSnapshotName", INTERNAL, "readDataSnapshotName", c =>
                c.register({
                    resourceName: expr.getLoose(
                        expr.deserializeRecord(
                            expr.jsonPathStrictSerialized(b.inputs.snapshotMigrationConfig, "snapshotNameResolution")),
                        "dataSnapshotResourceName")
                }), {
                    when: { templateExp: expr.hasKey(
                        expr.deserializeRecord(
                            expr.jsonPathStrictSerialized(b.inputs.snapshotMigrationConfig, "snapshotNameResolution")),
                        "dataSnapshotResourceName") },
                    dependencies: ["waitForSnapshot"] as const
                }
            )
            .addTask("migrateFromSnapshot", INTERNAL, "migrateFromSnapshot", c => {
                    const snapshotNameResolution = expr.deserializeRecord(
                        expr.jsonPathStrictSerialized(b.inputs.snapshotMigrationConfig, "snapshotNameResolution"));
                    const resolvedSnapshotName = expr.ternary(
                        expr.hasKey(snapshotNameResolution, "externalSnapshotName"),
                        expr.getLoose(snapshotNameResolution, "externalSnapshotName"),
                        c.tasks.readSnapshotName.outputs.snapshotName
                    );
                    const snapshotRepoConfig = expr.deserializeRecord(
                        expr.jsonPathStrictSerialized(b.inputs.snapshotMigrationConfig, "snapshotConfig"));

                    return c.register({
                        ...selectInputsForRegister(b, c),
                        ...selectInputsFieldsAsExpressionRecord(c.item, c,
                            getZodKeys(PER_INDICES_SNAPSHOT_MIGRATION_CONFIG)),
                        sourceVersion: expr.jsonPathStrict(b.inputs.snapshotMigrationConfig, "sourceVersion"),
                        sourceLabel: expr.jsonPathStrict(b.inputs.snapshotMigrationConfig, "sourceLabel"),
                        targetConfig: expr.jsonPathStrictSerialized(b.inputs.snapshotMigrationConfig, "targetConfig"),
                        snapshotConfig: expr.serialize(expr.makeDict({
                            snapshotName: resolvedSnapshotName,
                            label: expr.get(snapshotRepoConfig, "label"),
                            repoConfig: expr.get(snapshotRepoConfig, "repoConfig")
                        })),
                        migrationLabel: expr.get(c.item, "label"),
                        groupName: expr.get(c.item, "label")
                    });
                }, {
                    loopWith: makeParameterLoop(
                        expr.get(expr.deserializeRecord(b.inputs.snapshotMigrationConfig), "migrations")),
                    dependencies: ["readSnapshotName"] as const
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
                    ...selectInputsForRegister(b, c),
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
