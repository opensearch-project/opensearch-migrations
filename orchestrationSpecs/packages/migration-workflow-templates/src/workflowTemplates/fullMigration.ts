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
    ENRICHED_SNAPSHOT_MIGRATION_FILTER,
    getZodKeys,
    KAFKA_CLIENT_CONFIG, NAMED_KAFKA_CLIENT_CONFIG,
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
        .addRequiredInput("configChecksum", typeToken<string>())
        .addOptionalInput("groupName_view", c => "Kafka Cluster")
        .addOptionalInput("sortOrder_view", c => 999)

        .addSteps(b => b
            .addStep("deployCluster", SetupKafka, "deployKafkaClusterWithRetry", c =>
                c.register({
                    clusterName: b.inputs.clusterName,
                    version: b.inputs.version,
                    clusterConfig: expr.jsonPathStrictSerialized(b.inputs.kafkaClusterConfig, "config"),
                })
            )
            .addStep("stampChecksum", ResourceManagement, "patchConfigChecksumAnnotation", c =>
                c.register({
                    resourceApiVersion: expr.literal("kafka.strimzi.io/v1"),
                    resourceKind: expr.literal("Kafka"),
                    resourceName: b.inputs.clusterName,
                    configChecksum: b.inputs.configChecksum,
                })
            )
        )
    )


    // ── Section 2: Proxies ───────────────────────────────────────────────

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
            .addStep("setupProxy", SetupCapture, "setupProxyWithLifecycle", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    proxyConfig: b.inputs.proxyConfig,
                    kafkaClusterName: b.inputs.kafkaClusterName,
                    kafkaTopicName: b.inputs.kafkaTopicName,
                    proxyName: b.inputs.proxyName,
                    configChecksum: expr.dig(expr.deserializeRecord(b.inputs.proxyConfig), ["configChecksum"], ""),
                    checksumForSnapshot: expr.dig(expr.deserializeRecord(b.inputs.proxyConfig), ["checksumForSnapshot"], ""),
                    checksumForReplayer: expr.dig(expr.deserializeRecord(b.inputs.proxyConfig), ["checksumForReplayer"], ""),
                    listenPort: b.inputs.listenPort,
                    podReplicas: b.inputs.podReplicas,
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

        .addSteps(b => b
            .addStep("checkPhase", ResourceManagement, "readResourcePhase", c =>
                c.register({
                    resourceKind: expr.literal("DataSnapshot"),
                    resourceName: b.inputs.resourceName,
                })
            )
            .addStep("waitForProxyDeps", ResourceManagement, "waitForCapturedTraffic", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        resourceName: expr.get(c.item, "name"),
                        configChecksum: expr.get(c.item, "configChecksum"),
                        checksumField: expr.literal("checksumForSnapshot"),
                    }), {
                    loopWith: makeParameterLoop(
                        expr.get(expr.deserializeRecord(b.inputs.snapshotItemConfig), "dependsOnProxySetups")),
                    when: c => ({templateExp: expr.not(expr.and(
                        expr.equals(c.checkPhase.outputs.phase, "Completed"),
                        expr.equals(c.checkPhase.outputs.configChecksum, b.inputs.configChecksum)
                    ))}),
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
                    configChecksum: b.inputs.configChecksum,
                }),
                {when: c => ({templateExp: expr.not(expr.and(
                    expr.equals(c.checkPhase.outputs.phase, "Completed"),
                    expr.equals(c.checkPhase.outputs.configChecksum, b.inputs.configChecksum)
                ))})}
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


    .addTemplate("runSingleSnapshotMigration", t => t
        .addRequiredInput("snapshotMigrationConfig", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>())
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addOptionalInput("groupName_view", c => "Snapshot Migration")
        .addOptionalInput("sortOrder_view", c => 999)
        .addInputsFromRecord(uniqueRunNonceParam)
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("checkPhase", ResourceManagement, "readResourcePhase", c =>
                c.register({
                    resourceKind: expr.literal("SnapshotMigration"),
                    resourceName: b.inputs.resourceName,
                })
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
                        expr.not(expr.and(
                            expr.equals(c.checkPhase.outputs.phase, "Completed"),
                            expr.equals(c.checkPhase.outputs.configChecksum, b.inputs.configChecksum)
                        )),
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
                        expr.not(expr.and(
                            expr.equals(c.checkPhase.outputs.phase, "Completed"),
                            expr.equals(c.checkPhase.outputs.configChecksum, b.inputs.configChecksum)
                        )),
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
                        ...selectInputsFieldsAsExpressionRecord(c.item, c,
                            getZodKeys(PER_INDICES_SNAPSHOT_MIGRATION_CONFIG)),
                        sourceVersion: expr.get(snapshotMigrationConfig, "sourceVersion"),
                        sourceLabel: expr.get(snapshotMigrationConfig, "sourceLabel"),
                        targetConfig: expr.serialize(expr.get(snapshotMigrationConfig, "targetConfig")),
                        snapshotConfig: expr.serialize(expr.makeDict({
                            snapshotName: resolvedSnapshotName,
                            label: expr.get(snapshotRepoConfig, "label"),
                            repoConfig: expr.get(snapshotRepoConfig, "repoConfig")
                        })),
                        migrationLabel: expr.get(c.item, "label"),
                        groupName_view: expr.get(c.item, "label"),
                        sourceEndpoint: expr.dig(snapshotMigrationConfig, ["sourceEndpoint"], "")
                    });
                }, {
                    loopWith: makeParameterLoop(
                        expr.get(expr.deserializeRecord(b.inputs.snapshotMigrationConfig), "migrations")),
                    when: c => ({templateExp: expr.not(expr.and(
                        expr.equals(c.checkPhase.outputs.phase, "Completed"),
                        expr.equals(c.checkPhase.outputs.configChecksum, b.inputs.configChecksum)
                    ))}),
                }
            )
            .addStep("patchSnapshotMigration", ResourceManagement, "patchSnapshotMigrationReady", c =>
                c.register({
                    resourceName: b.inputs.resourceName,
                    configChecksum: b.inputs.configChecksum,
                    checksumForReplayer: expr.dig(
                        expr.deserializeRecord(b.inputs.snapshotMigrationConfig),
                        ["checksumForReplayer"], ""
                    ),
                }),
                {when: c => ({templateExp: expr.not(expr.and(
                    expr.equals(c.checkPhase.outputs.phase, "Completed"),
                    expr.equals(c.checkPhase.outputs.configChecksum, b.inputs.configChecksum)
                ))})}
            )
        )
    )


    // ── Section 5: Traffic Replays ───────────────────────────────────────

    .addTemplate("runSingleReplay", t => t
        .addRequiredInput("kafkaConfig", typeToken<z.infer<typeof NAMED_KAFKA_CLIENT_CONFIG>>())
        .addRequiredInput("kafkaClusterName", typeToken<string>())
        .addRequiredInput("fromProxy", typeToken<string>())
        .addRequiredInput("fromProxyConfigChecksum", typeToken<string>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("replayerOptions", typeToken<z.infer<typeof ARGO_REPLAYER_OPTIONS>>())
        .addRequiredInput("name", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addOptionalInput("groupName_view", c => "Traffic Replay")
        .addOptionalInput("sortOrder_view", c => 999)
        .addRequiredInput("dependsOnSnapshotMigrations", typeToken<z.infer<typeof ENRICHED_SNAPSHOT_MIGRATION_FILTER>[]>())

        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole", "TrafficReplayer"]))

        .addSteps(b => b
            .addStep("checkPhase", ResourceManagement, "readResourcePhase", c =>
                c.register({
                    resourceKind: expr.literal("TrafficReplay"),
                    resourceName: b.inputs.name,
                })
            )
            .addStep("waitForSnapshotMigrationDeps", ResourceManagement, "waitForSnapshotMigration", c => {
                    return c.register({
                        ...selectInputsForRegister(b, c),
                        resourceName: expr.concat(
                            expr.asString(expr.get(c.item, "source")),
                            expr.literal("-"),
                            expr.asString(expr.get(c.item, "snapshot"))
                        ),
                        configChecksum: expr.dig(c.item, ["configChecksum"], expr.literal("")),
                        checksumField: expr.literal("checksumForReplayer"),
                    });
                }, {
                    loopWith: makeParameterLoop(expr.deserializeRecord(b.inputs.dependsOnSnapshotMigrations)),
                    when: c => ({templateExp: expr.and(
                        expr.not(expr.and(
                            expr.equals(c.checkPhase.outputs.phase, "Ready"),
                            expr.equals(c.checkPhase.outputs.configChecksum, b.inputs.configChecksum)
                        )),
                        expr.not(expr.isEmpty(expr.deserializeRecord(b.inputs.dependsOnSnapshotMigrations)))
                    )}),
                }
            )
            .addStep("waitForProxy", ResourceManagement, "waitForCapturedTraffic", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: b.inputs.fromProxy,
                    configChecksum: b.inputs.fromProxyConfigChecksum,
                    checksumField: expr.literal("checksumForReplayer"),
                }),
                {when: c => ({templateExp: expr.not(expr.and(
                    expr.equals(c.checkPhase.outputs.phase, "Ready"),
                    expr.equals(c.checkPhase.outputs.configChecksum, b.inputs.configChecksum)
                ))})}
            )
            .addStep("waitForKafkaCluster", ResourceManagement, "waitForKafkaCluster", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    resourceName: b.inputs.kafkaClusterName,
                    configChecksum: expr.dig(
                        expr.deserializeRecord(b.inputs.kafkaConfig),
                        ["configChecksum"],
                        ""
                    ),
                })
            , {
                when: c => ({templateExp: expr.and(
                    expr.not(expr.and(
                        expr.equals(c.checkPhase.outputs.phase, "Ready"),
                        expr.equals(c.checkPhase.outputs.configChecksum, b.inputs.configChecksum)
                    )),
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
                    expr.not(expr.and(
                        expr.equals(c.checkPhase.outputs.phase, "Ready"),
                        expr.equals(c.checkPhase.outputs.configChecksum, b.inputs.configChecksum)
                    )),
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
                    name: expr.concat(
                        b.inputs.fromProxy,
                        expr.literal("-"),
                        expr.get(expr.deserializeRecord(b.inputs.targetConfig), "label"),
                        expr.literal("-replayer"))
                }),
                {when: c => ({templateExp: expr.not(expr.and(
                    expr.equals(c.checkPhase.outputs.phase, "Ready"),
                    expr.equals(c.checkPhase.outputs.configChecksum, b.inputs.configChecksum)
                ))})}
            )
            .addStep("markReady", ResourceManagement, "patchResourcePhase", c =>
                c.register({
                    resourceKind: expr.literal("TrafficReplay"),
                    resourceName: b.inputs.name,
                    phase: expr.literal("Ready"),
                    configChecksum: b.inputs.configChecksum,
                }),
                {when: c => ({templateExp: expr.not(expr.and(
                    expr.equals(c.checkPhase.outputs.phase, "Ready"),
                    expr.equals(c.checkPhase.outputs.configChecksum, b.inputs.configChecksum)
                ))})}
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

        .addSteps(b => b.addStepGroup(g => g
            .addStep("createKafka", INTERNAL, "setupSingleKafkaCluster", c =>
                c.register({
                    kafkaClusterConfig: expr.serialize(expr.makeDict({
                        name: expr.get(c.item, "name"),
                        version: expr.get(c.item, "version"),
                        config: expr.deserializeRecord(expr.get(c.item, "config")),
                        topics: expr.deserializeRecord(expr.get(c.item, "topics")),
                        configChecksum: expr.get(c.item, "configChecksum")
                    })),
                    //kafkaClusterConfig: expr.cast(c.item).to<Serialized<z.infer<typeof NAMED_KAFKA_CLUSTER_CONFIG>>>(),
                    clusterName: expr.get(c.item, "name"),
                    version: expr.cast(expr.get(c.item, "version")).to<string>(),
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
                        kafkaConfig: expr.deserializeRecord(expr.get(c.item, "kafkaConfig")),
                        sourceEndpoint: expr.get(c.item, "sourceEndpoint"),
                        sourceAllowInsecure: expr.get(c.item, "sourceAllowInsecure"),
                        proxyConfig: expr.deserializeRecord(expr.get(c.item, "proxyConfig")),
                        configChecksum: expr.dig(c.item, ["configChecksum"], ""),
                        checksumForSnapshot: expr.dig(c.item, ["checksumForSnapshot"], ""),
                        checksumForReplayer: expr.dig(c.item, ["checksumForReplayer"], ""),
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
                        checksumForReplayer: expr.dig(c.item, ["checksumForReplayer"], ""),
                    })),
//                    snapshotMigrationConfig: expr.cast(c.item).to<Serialized<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>>()
                    configChecksum: expr.dig(c.item, ["configChecksum"], ""),
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
