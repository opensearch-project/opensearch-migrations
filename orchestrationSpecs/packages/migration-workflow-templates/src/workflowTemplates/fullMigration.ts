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
    selectInputsForRegister, Serialized, TemplateBuilder,
    typeToken, WorkflowAndTemplatesScope,
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


    // ── Section 1: Kafka Clusters ────────────────────────────────────────

    .addTemplate("setupSingleKafkaCluster", t => t
        .addRequiredInput("kafkaClusterConfig", typeToken<z.infer<typeof NAMED_KAFKA_CLUSTER_CONFIG>>())
        .addRequiredInput("clusterName",        typeToken<string>())
        .addRequiredInput("version",            typeToken<string>())

        .addSteps(b => b
            .addStep("deployCluster", SetupKafka, "deployKafkaCluster", c =>
                c.register({
                    clusterName:   b.inputs.clusterName,
                    version:       b.inputs.version,
                    clusterConfig: expr.jsonPathStrictSerialized(b.inputs.kafkaClusterConfig, "config"),
                })
            )
        )
    )


    // ── Section 2: Proxies ───────────────────────────────────────────────

    .addTemplate("setupSingleProxy", t => t
        .addRequiredInput("proxyConfig",      typeToken<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>())
        .addRequiredInput("kafkaClusterName", typeToken<string>())
        .addRequiredInput("kafkaTopicName",   typeToken<string>())
        .addRequiredInput("proxyName",        typeToken<string>())
        .addRequiredInput("listenPort",       typeToken<number>())
        .addRequiredInput("podReplicas",      typeToken<number>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole", "CaptureProxy"]))

        .addSteps(b => b
            .addStep("waitForKafkaCluster", ResourceManagement, "waitForKafkaCluster", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: b.inputs.kafkaClusterName,
                })
            )
            .addStep("setupProxy", SetupCapture, "setupProxy", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    proxyConfig:      b.inputs.proxyConfig,
                    kafkaClusterName: b.inputs.kafkaClusterName,
                    kafkaTopicName:   b.inputs.kafkaTopicName,
                    proxyName:        b.inputs.proxyName,
                    listenPort:       b.inputs.listenPort,
                    podReplicas:      b.inputs.podReplicas,
                })
            )
            .addStep("patchCapturedTraffic", ResourceManagement, "patchCapturedTrafficReady", c =>
                c.register({
                    resourceName: b.inputs.proxyName,
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
        .addInputsFromRecord(uniqueRunNonceParam)
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("waitForProxyDeps", ResourceManagement, "waitForCapturedTraffic", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        resourceName: c.item
                    }), {
                    loopWith: makeParameterLoop(
                        expr.ternary(
                            expr.hasKey(expr.deserializeRecord(b.inputs.snapshotItemConfig),
                                "dependsOnProxySetups"),
                            expr.getLoose(expr.deserializeRecord(b.inputs.snapshotItemConfig),
                                "dependsOnProxySetups"),
                            expr.literal([]))),
                    when: { templateExp: expr.not(expr.isEmpty(
                        expr.dig(expr.deserializeRecord(b.inputs.snapshotItemConfig), ["dependsOnProxySetups"], [])))
                    }
                }
            )
            .addStep("createOrGetSnapshot", CreateOrGetSnapshot, "createOrGetSnapshot", c =>
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
                })
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
                    snapshotItemConfig: expr.serialize(expr.makeDict({
                        label: expr.get(c.item, "label"),
                        snapshotPrefix: expr.get(c.item, "snapshotPrefix"),
                        config: expr.deserializeRecord(expr.get(c.item, "config")),
                        repo: expr.deserializeRecord(expr.get(c.item, "repo")),
                        semaphoreConfigMapName: expr.get(c.item, "semaphoreConfigMapName"),
                        semaphoreKey: expr.get(c.item, "semaphoreKey"),
                        dependsOnProxySetups: expr.getLoose(
                            expr.deserializeRecord(expr.recordToString(c.item)),
                            "dependsOnProxySetups"
                        )
                    })),
//                    snapshotItemConfig: expr.cast(c.item).to<Serialized<z.infer<typeof PER_SOURCE_CREATE_SNAPSHOTS_CONFIG>>>(),
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
        )
    )


    .addTemplate("runSingleSnapshotMigration", t => t
        .addRequiredInput("snapshotMigrationConfig", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>())
        .addRequiredInput("resourceName", typeToken<string>())
        .addInputsFromRecord(uniqueRunNonceParam)
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("waitForSnapshot", ResourceManagement, "waitForDataSnapshot", c =>
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
            .addStep("readSnapshotName", ResourceManagement, "readDataSnapshotName", c =>
                c.register({
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
            .addStep("migrateFromSnapshot", INTERNAL, "migrateFromSnapshot", c => {
                    const snapshotNameResolution = expr.deserializeRecord(
                        expr.jsonPathStrictSerialized(b.inputs.snapshotMigrationConfig, "snapshotNameResolution"));
                    const resolvedSnapshotName = expr.ternary(
                        expr.hasKey(snapshotNameResolution, "externalSnapshotName"),
                        expr.getLoose(snapshotNameResolution, "externalSnapshotName"),
                        c.steps.readSnapshotName.outputs.snapshotName
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
                        expr.get(expr.deserializeRecord(b.inputs.snapshotMigrationConfig), "migrations"))
                }
            )
            .addStep("patchSnapshotMigration", ResourceManagement, "patchSnapshotMigrationReady", c =>
                c.register({...selectInputsForRegister(b, c)})
            )
        )
    )


    // ── Section 5: Traffic Replays ───────────────────────────────────────

    .addTemplate("runSingleReplay", t => t
        .addRequiredInput("replayConfig", typeToken<z.infer<typeof DENORMALIZED_REPLAY_CONFIG>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addSteps(b => b
            .addStep("waitForSnapshotMigrationDeps", ResourceManagement, "waitForSnapshotMigration", c => {
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
            .addStep("waitForProxy", ResourceManagement, "waitForCapturedTraffic", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: expr.jsonPathStrict(b.inputs.replayConfig, "fromProxy"),
                })
            )
            .addStep("waitForKafkaCluster", ResourceManagement, "waitForKafkaCluster", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: expr.jsonPathStrict(b.inputs.replayConfig, "kafkaClusterName"),
                })
            )
            .addStep("placeholderReplay", INTERNAL, "doNothing")
        )
    )


    // ── Main ─────────────────────────────────────────────────────────────

    .addTemplate("main", t => t
        .addRequiredInput("config",
            typeToken<z.infer<typeof ARGO_MIGRATION_CONFIG>>(),
            "Full migration configuration")
        .addRequiredInput("uniqueRunNonce", typeToken<string>())
        .addInputsFromRecord(defaultImagesMap(t.inputs.workflowParameters.imageConfigMapName))

        .addSteps(b => b.addStepGroup(g => g
            .addStep("setupKafkaClusters", INTERNAL, "setupSingleKafkaCluster", c =>
                c.register({
                    kafkaClusterConfig: expr.serialize(expr.makeDict({
                        name: expr.get(c.item, "name"),
                        version: expr.get(c.item, "version"),
                        config: expr.deserializeRecord(expr.get(c.item, "config")),
                        topics: expr.deserializeRecord(expr.get(c.item, "topics"))
                    })),
                    //kafkaClusterConfig: expr.cast(c.item).to<Serialized<z.infer<typeof NAMED_KAFKA_CLUSTER_CONFIG>>>(),
                    clusterName: expr.get(c.item, "name"),
                    version:     expr.cast(expr.get(c.item, "version")).to<string>(),
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
            .addStep("setupProxies", INTERNAL, "setupSingleProxy", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    proxyConfig:      expr.serialize(expr.makeDict({
                        name: expr.get(c.item, "name"),
                        kafkaConfig: expr.deserializeRecord(expr.get(c.item, "kafkaConfig")),
                        sourceEndpoint: expr.get(c.item, "sourceEndpoint"),
                        proxyConfig: expr.deserializeRecord(expr.get(c.item, "proxyConfig"))
                    })),
                    // proxyConfig:      expr.cast(c.item).to<Serialized<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>>(),
                    kafkaClusterName: expr.dig(
                        expr.deserializeRecord(expr.get(c.item, "kafkaConfig")),
                        ["label"],
                        ""
                    ),
                    kafkaTopicName:   expr.dig(
                        expr.deserializeRecord(expr.get(c.item, "kafkaConfig")),
                        ["kafkaTopic"],
                        ""
                    ),
                    proxyName:        expr.get(c.item, "name"),
                    listenPort:       expr.dig(
                        expr.deserializeRecord(expr.get(c.item, "proxyConfig")),
                        ["listenPort"],
                        9200
                    ),
                    podReplicas:      expr.dig(
                        expr.deserializeRecord(expr.get(c.item, "proxyConfig")),
                        ["podReplicas"],
                        1
                    ),
                }), {
                    loopWith: makeParameterLoop(
                        expr.get(expr.deserializeRecord(b.inputs.config), "proxies"))
                }
            )
            .addStep("createSnapshots", INTERNAL, "createSnapshotsForSource", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    snapshotsSourceConfig: expr.serialize(expr.makeDict({
                        createSnapshotConfig: expr.deserializeRecord(expr.get(c.item, "createSnapshotConfig")),
                        sourceConfig: expr.deserializeRecord(expr.get(c.item, "sourceConfig"))
                    }))
                    //snapshotsSourceConfig: expr.cast(c.item).to<Serialized<z.infer<typeof DENORMALIZED_CREATE_SNAPSHOTS_CONFIG>>>()
                }), {
                    loopWith: makeParameterLoop(
                        expr.get(expr.deserializeRecord(b.inputs.config), "snapshots"))
                }
            )
            .addStep("runSnapshotMigrations", INTERNAL, "runSingleSnapshotMigration", c =>
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
                    snapshotMigrationConfig: expr.serialize(expr.makeDict({
                        label: expr.get(c.item, "label"),
                        snapshotNameResolution: expr.deserializeRecord(expr.get(c.item, "snapshotNameResolution")),
                        migrations: expr.deserializeRecord(expr.get(c.item, "migrations")),
                        sourceVersion: expr.get(c.item, "sourceVersion"),
                        sourceLabel: expr.get(c.item, "sourceLabel"),
                        targetConfig: expr.deserializeRecord(expr.get(c.item, "targetConfig")),
                        snapshotConfig: expr.deserializeRecord(expr.get(c.item, "snapshotConfig"))
                    })),
//                    snapshotMigrationConfig: expr.cast(c.item).to<Serialized<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>>()
                }), {
                    loopWith: makeParameterLoop(
                        expr.get(expr.deserializeRecord(b.inputs.config), "snapshotMigrations"))
                }
            )
            .addStep("runTrafficReplays", INTERNAL, "runSingleReplay", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    replayConfig: expr.serialize(expr.makeDict({
                        fromProxy: expr.get(c.item, "fromProxy"),
                        kafkaClusterName: expr.get(c.item, "kafkaClusterName"),
                        kafkaConfig: expr.deserializeRecord(expr.get(c.item, "kafkaConfig")),
                        toTarget: expr.deserializeRecord(expr.get(c.item, "toTarget")),
                        dependsOnSnapshotMigrations: expr.getLoose(
                            expr.deserializeRecord(expr.recordToString(c.item)),
                            "dependsOnSnapshotMigrations"
                        ),
                        replayerConfig: expr.getLoose(
                            expr.deserializeRecord(expr.recordToString(c.item)),
                            "replayerConfig"
                        )
                    }))
                    // replayConfig: expr.cast(c.item).to<Serialized<z.infer<typeof DENORMALIZED_REPLAY_CONFIG>>>()
                }), {
                    loopWith: makeParameterLoop(
                        expr.get(expr.deserializeRecord(b.inputs.config), "trafficReplays"))
                }
            )
        ))
    )


    .setEntrypoint("main")
    .getFullScope();
