import {z} from 'zod';
import {
    AllowLiteralOrExpression,
    BaseExpression,
    expr,
    FunctionExpression,
    INTERNAL,
    InputParamDef,
    InputParametersRecord,
    makeDirectTypeProxy,
    makeStringTypeProxy,
    NonSerializedPlainObject,
    PlainObject,
    selectInputsForRegister,
    Serialized,
    TemplateBuilder,
    typeToken,
    WorkflowAndTemplatesScope,
    WorkflowBuilder
} from '@opensearch-migrations/argo-workflow-builders';
import {
    ARGO_PROXY_CR_OMITTED_KEYS,
    ARGO_REPLAYER_OPTIONS,
    ARGO_REPLAYER_WORKFLOW_OPTION_KEYS,
    DEFAULT_RESOURCES,
    DENORMALIZED_PROXY_CONFIG,
    NAMED_KAFKA_CLUSTER_CONFIG,
    PER_SOURCE_CREATE_SNAPSHOTS_CONFIG,
    SNAPSHOT_MIGRATION_CONFIG,
} from '@opensearch-migrations/schemas';
import {CommonWorkflowParameters, workflowScriptCommand, workflowScriptRootEnvVars} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {
    KAFKA_CLUSTER_READY_TIMEOUT_SECONDS,
    KAFKA_READY_WAIT_POD_RETRY_STRATEGY,
    K8S_INFRA_READY_RETRY_STRATEGY,
    K8S_INFRA_READY_TIMEOUT_SECONDS,
    K8S_RESOURCE_RETRY_STRATEGY,
    K8S_INDEFINITE_RESOURCE_WAIT_RETRY_STRATEGY,
    K8S_SECRET_READY_RETRY_STRATEGY,
    K8S_USER_APPROVAL_WAIT_RETRY_STRATEGY,
} from "./commonUtils/resourceRetryStrategy";

const CRD_API_VERSION = "migrations.opensearch.org/v1alpha1";
const RUN_NUMBER_LABEL = "migrations.opensearch.org/run-number";
const APPROVED_DURING_RUN_ANNOTATION = "migrations.opensearch.org/approved-during-run";
const MIGRATION_RUN_NUMBER_VALUE = "{{workflow.parameters.migrationRunNumber}}";
const WORKFLOW_LABEL = "workflows.argoproj.io/workflow";
const SOURCE_LABEL = "migrations.opensearch.org/source";
const TARGET_LABEL = "migrations.opensearch.org/target";
const SNAPSHOT_LABEL = "migrations.opensearch.org/snapshot";
const MIGRATION_LABEL = "migrations.opensearch.org/from-snapshot-migration";
const TASK_LABEL = "migrations.opensearch.org/task";
const KAFKA_CLUSTER_LABEL = "migrations.opensearch.org/kafka-cluster";
const STRIMZI_CLUSTER_LABEL = "strimzi.io/cluster";
const KAFKA_READY_WAIT_ACTIVE_DEADLINE_SECONDS = KAFKA_CLUSTER_READY_TIMEOUT_SECONDS + 60;

type ReservedPatchInputNames = "resourceName" | "phase";
type StringStatusFields = Readonly<Record<string, AllowLiteralOrExpression<string>>>;
type NonReservedStringStatusFields = StringStatusFields & {
    [K in ReservedPatchInputNames]?: never;
};
type RequiredStringInputDefs<T extends StringStatusFields> = {
    [K in keyof T]: InputParamDef<string, true>;
};
type NamedPatchRegisterValues<T extends StringStatusFields> = {
    resourceName: AllowLiteralOrExpression<string>;
    phase: AllowLiteralOrExpression<string>;
} & T;

function makeRequiredStringInputDefs<T extends StringStatusFields>(fields: T): RequiredStringInputDefs<T> {
    const defs = {} as RequiredStringInputDefs<T>;
    for (const key of Object.keys(fields) as Array<keyof T>) {
        defs[key] = {} as InputParamDef<string, true>;
    }
    return defs;
}

function placeholderStatusFields<T extends StringStatusFields>(fields: T): Record<string, string> {
    const proxied: Record<string, string> = {};
    for (const key of Object.keys(fields) as Array<keyof T>) {
        proxied[String(key)] = `{{inputs.parameters.${String(key)}}}`;
    }
    return proxied;
}

function makeYamlJsonLiteralProxy<T extends NonSerializedPlainObject>(value: BaseExpression<T, any>): T {
    // Resource templates substitute Argo expressions before kubectl parses the YAML.
    // toJSON keeps quote-heavy strings, arrays, and objects valid as YAML literals.
    const jsonExpression = new FunctionExpression<Serialized<T>, NonSerializedPlainObject, any>(
        "toJSON",
        [value] as unknown as BaseExpression<NonSerializedPlainObject, any>[]
    );
    return makeDirectTypeProxy(jsonExpression as unknown as BaseExpression<Record<string, PlainObject>>) as unknown as T;
}

function buildPatchStatusTemplate<
    ParentWorkflowScope extends WorkflowAndTemplatesScope,
    ExtraFields extends NonReservedStringStatusFields = {}
>(
    t: TemplateBuilder<ParentWorkflowScope, {}, {}, {}>,
    resourceKind: string,
    extraStatusFields: ExtraFields
) {
    const inputDefs = {
        resourceName: {} as InputParamDef<string, true>,
        phase: {} as InputParamDef<string, true>,
        ...makeRequiredStringInputDefs(extraStatusFields)
    } satisfies InputParametersRecord;

    return t
        .addInputsFromRecord(inputDefs)
        .addResourceTask(b => b
            .setDefinition({
                action: "patch",
                flags: ["--type", "merge", "--subresource=status"],
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: resourceKind,
                    metadata: {name: "{{inputs.parameters.resourceName}}"},
                    status: {
                        phase: "{{inputs.parameters.phase}}",
                        ...placeholderStatusFields(extraStatusFields)
                    }
                }
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY);
}

function buildPatchOutputTemplate<ParentWorkflowScope extends WorkflowAndTemplatesScope>(
    t: TemplateBuilder<ParentWorkflowScope, {}, {}, {}>,
    resourceKind: string,
    outputName: string
) {
    return t
        .addInputsFromRecord({
            resourceName: {} as InputParamDef<string, true>,
            artifactName: {} as InputParamDef<string, true>,
            s3Key: {} as InputParamDef<string, true>,
            resourceUid: {} as InputParamDef<string, true>,
            configChecksum: {} as InputParamDef<string, true>,
        })
        .addResourceTask(b => b
            .setDefinition({
                action: "patch",
                flags: ["--type", "merge", "--subresource=status"],
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: resourceKind,
                    metadata: {name: "{{inputs.parameters.resourceName}}"},
                    status: {
                        outputs: {
                            [outputName]: {
                                artifactName: "{{inputs.parameters.artifactName}}",
                                s3Key: "{{inputs.parameters.s3Key}}",
                                resourceUid: "{{inputs.parameters.resourceUid}}",
                                workflowName: "{{workflow.name}}",
                                migrationRunNumber: MIGRATION_RUN_NUMBER_VALUE,
                                workflowCreationTimestamp: "{{workflow.creationTimestamp}}",
                                configChecksum: "{{inputs.parameters.configChecksum}}"
                            }
                        }
                    }
                }
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY);
}

function makeKafkaClusterManifest(
    kafkaClusterConfig: BaseExpression<Serialized<z.infer<typeof NAMED_KAFKA_CLUSTER_CONFIG>>>,
) {
    const kc = expr.deserializeRecord(kafkaClusterConfig);
    const config = expr.get(kc, "config");
    return {
        apiVersion: CRD_API_VERSION,
        kind: "KafkaCluster",
        metadata: {
            name: makeStringTypeProxy(expr.get(kc, "name")),
            labels: {
                [WORKFLOW_LABEL]: makeStringTypeProxy(expr.getWorkflowValue("name")),
                [RUN_NUMBER_LABEL]: MIGRATION_RUN_NUMBER_VALUE,
                [STRIMZI_CLUSTER_LABEL]: makeStringTypeProxy(expr.get(kc, "name")),
            }
        },
        spec: {
            dependsOn: [],
            version: makeStringTypeProxy(expr.get(kc, "version")),
            auth: {
                type: makeStringTypeProxy(expr.dig(config, ["auth", "type"], "none")),
            },
            nodePool: {
                replicas: makeDirectTypeProxy(expr.dig(config, ["nodePoolSpecOverrides", "replicas"], 1)),
                roles: makeDirectTypeProxy(expr.dig(
                    config,
                    ["nodePoolSpecOverrides", "roles"],
                    expr.literal(["controller", "broker"])
                )),
                storage: {
                    size: makeStringTypeProxy(expr.dig(
                        config,
                        ["nodePoolSpecOverrides", "storage", "size"],
                        expr.literal("5Gi"))
                    ),
                    type: makeStringTypeProxy(expr.dig(
                        config,
                        ["nodePoolSpecOverrides", "storage", "type"],
                        expr.literal("persistent-claim"))
                    ),
                },
            },
        }
    };
}

function makeCapturedTrafficManifest(
    topicCrName: BaseExpression<string>,
    kafkaClusterName: BaseExpression<string>,
    kafkaTopicName: BaseExpression<string>,
    sourceLabel: BaseExpression<string>,
    partitions: BaseExpression<Serialized<number>>,
    replicas: BaseExpression<Serialized<number>>,
    topicConfig: BaseExpression<Serialized<Record<string, any>>>,
) {
    return {
        apiVersion: CRD_API_VERSION,
        kind: "CapturedTraffic",
        metadata: {
            name: makeStringTypeProxy(topicCrName),
            labels: {
                [WORKFLOW_LABEL]: makeStringTypeProxy(expr.getWorkflowValue("name")),
                [RUN_NUMBER_LABEL]: MIGRATION_RUN_NUMBER_VALUE,
                [SOURCE_LABEL]: makeStringTypeProxy(sourceLabel),
                [KAFKA_CLUSTER_LABEL]: makeStringTypeProxy(kafkaClusterName),
            }
        },
        spec: {
            dependsOn: [makeStringTypeProxy(kafkaClusterName)],
            kafkaClusterName: makeStringTypeProxy(kafkaClusterName),
            topicName: makeStringTypeProxy(kafkaTopicName),
            partitions: makeDirectTypeProxy(partitions),
            replicas: makeDirectTypeProxy(replicas),
            topicConfig: makeDirectTypeProxy(expr.deserializeRecord(topicConfig)),
        }
    };
}

function makeCaptureProxyManifest(
    proxyConfig: BaseExpression<Serialized<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>>,
    proxyName: BaseExpression<string>,
    topicCrName: BaseExpression<string>,
) {
    const config = expr.deserializeRecord(proxyConfig);
    const proxyOpts = expr.get(config, "proxyConfig");
    const workflowSpecFields = expr.makeDict({
        dependsOn: expr.toArray(topicCrName),
        loggingConfigurationOverrideConfigMap: expr.dig(
            proxyOpts,
            ["loggingConfigurationOverrideConfigMap"],
            expr.literal("")
        ),
        internetFacing: expr.dig(proxyOpts, ["internetFacing"], false),
        podReplicas: expr.dig(proxyOpts, ["podReplicas"], 1),
        resources: expr.get(proxyOpts, "resources"),
        tls: expr.dig(proxyOpts, ["tls"], expr.makeDict({})),
    });
    return {
        apiVersion: CRD_API_VERSION,
        kind: "CaptureProxy",
        metadata: {
            name: makeStringTypeProxy(proxyName),
            labels: {
                [WORKFLOW_LABEL]: makeStringTypeProxy(expr.getWorkflowValue("name")),
                [RUN_NUMBER_LABEL]: MIGRATION_RUN_NUMBER_VALUE,
                [SOURCE_LABEL]: makeStringTypeProxy(expr.dig(config, ["sourceConfig", "label"], "")),
                [TASK_LABEL]: "captureProxy",
            }
        },
        spec: makeDirectTypeProxy(expr.mergeDicts(
            workflowSpecFields,
            expr.omit(proxyOpts, ...ARGO_PROXY_CR_OMITTED_KEYS)
        )),
    };
}

function makeSnapshotMigrationManifest(
    resourceName: BaseExpression<string>,
    snapshotMigrationConfig: BaseExpression<Serialized<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>>,
) {
    const config = expr.deserializeRecord(snapshotMigrationConfig);
    return {
        apiVersion: CRD_API_VERSION,
        kind: "SnapshotMigration",
        metadata: {
            name: makeStringTypeProxy(resourceName),
            labels: {
                [WORKFLOW_LABEL]: makeStringTypeProxy(expr.getWorkflowValue("name")),
                [RUN_NUMBER_LABEL]: MIGRATION_RUN_NUMBER_VALUE,
                [SOURCE_LABEL]: makeStringTypeProxy(expr.get(config, "sourceLabel")),
                [TARGET_LABEL]: makeStringTypeProxy(expr.dig(config, ["targetConfig", "label"], expr.literal(""))),
                [SNAPSHOT_LABEL]: makeStringTypeProxy(expr.dig(config, ["snapshotConfig", "label"], expr.literal(""))),
                [MIGRATION_LABEL]: makeStringTypeProxy(expr.get(config, "migrationLabel")),
            }
        },
        spec: {
            migrationLabel: makeStringTypeProxy(expr.get(config, "migrationLabel")),
            sourceVersion: makeStringTypeProxy(expr.get(config, "sourceVersion")),
            sourceLabel: makeStringTypeProxy(expr.get(config, "sourceLabel")),
            targetLabel: makeStringTypeProxy(expr.dig(config, ["targetConfig", "label"], expr.literal(""))),
            snapshotLabel: makeStringTypeProxy(expr.dig(config, ["snapshotConfig", "label"], expr.literal(""))),
            metadataMigrationJvmArgs: makeStringTypeProxy(expr.dig(config, ["metadataMigrationConfig", "jvmArgs"], expr.literal(""))),
            metadataMigrationLoggingConfigurationOverrideConfigMap: makeStringTypeProxy(expr.dig(config, ["metadataMigrationConfig", "loggingConfigurationOverrideConfigMap"], expr.literal(""))),
            metadataMigrationComponentTemplateAllowlist: makeDirectTypeProxy(expr.dig(config, ["metadataMigrationConfig", "componentTemplateAllowlist"], expr.literal([]))),
            metadataMigrationIndexAllowlist: makeDirectTypeProxy(expr.dig(config, ["metadataMigrationConfig", "indexAllowlist"], expr.literal([]))),
            metadataMigrationIndexTemplateAllowlist: makeDirectTypeProxy(expr.dig(config, ["metadataMigrationConfig", "indexTemplateAllowlist"], expr.literal([]))),
            metadataMigrationAllowLooseVersionMatching: makeDirectTypeProxy(expr.dig(config, ["metadataMigrationConfig", "allowLooseVersionMatching"], true)),
            metadataMigrationClusterAwarenessAttributes: makeDirectTypeProxy(expr.dig(config, ["metadataMigrationConfig", "clusterAwarenessAttributes"], 1)),
            metadataMigrationEnableSourcelessMigrations: makeDirectTypeProxy(expr.dig(config, ["metadataMigrationConfig", "enableSourcelessMigrations"], false)),
            metadataMigrationUseRecoverySource: makeDirectTypeProxy(expr.dig(config, ["metadataMigrationConfig", "useRecoverySource"], false)),
            metadataMigrationOtelCollectorEndpoint: makeStringTypeProxy(expr.dig(config, ["metadataMigrationConfig", "otelCollectorEndpoint"], expr.literal(""))),
            metadataMigrationOutput: makeStringTypeProxy(expr.dig(config, ["metadataMigrationConfig", "output"], expr.literal("HUMAN_READABLE"))),
            metadataMigrationTransformerConfigBase64: makeStringTypeProxy(expr.dig(config, ["metadataMigrationConfig", "transformerConfigBase64"], expr.literal(""))),
            metadataMigrationTransformerConfig: makeYamlJsonLiteralProxy(expr.dig(config, ["metadataMigrationConfig", "transformerConfig"], expr.literal(""))),
            metadataMigrationTransformerConfigFile: makeStringTypeProxy(expr.dig(config, ["metadataMigrationConfig", "transformerConfigFile"], expr.literal(""))),
            metadataMigrationFileSourceVolumes: makeYamlJsonLiteralProxy(expr.dig(config, ["metadataMigrationConfig", "fileSourceVolumes"], expr.literal([]))),
            metadataMigrationFileSourceVolumeMounts: makeYamlJsonLiteralProxy(expr.dig(config, ["metadataMigrationConfig", "fileSourceVolumeMounts"], expr.literal([]))),
            documentBackfillPodReplicas: makeDirectTypeProxy(expr.dig(config, ["documentBackfillConfig", "podReplicas"], 1)),
            documentBackfillJvmArgs: makeStringTypeProxy(expr.dig(config, ["documentBackfillConfig", "jvmArgs"], expr.literal(""))),
            documentBackfillLoggingConfigurationOverrideConfigMap: makeStringTypeProxy(expr.dig(config, ["documentBackfillConfig", "loggingConfigurationOverrideConfigMap"], expr.literal(""))),
            documentBackfillUseTargetClusterForWorkCoordination: makeDirectTypeProxy(expr.dig(config, ["documentBackfillConfig", "useTargetClusterForWorkCoordination"], false)),
            documentBackfillResources: makeDirectTypeProxy(expr.dig(config, ["documentBackfillConfig", "resources"], expr.makeDict({}))),
            documentBackfillIndexAllowlist: makeDirectTypeProxy(expr.dig(config, ["documentBackfillConfig", "indexAllowlist"], expr.literal([]))),
            documentBackfillAllowLooseVersionMatching: makeDirectTypeProxy(expr.dig(config, ["documentBackfillConfig", "allowLooseVersionMatching"], true)),
            documentBackfillEnableSourcelessMigrations: makeDirectTypeProxy(expr.dig(config, ["documentBackfillConfig", "enableSourcelessMigrations"], false)),
            documentBackfillUseRecoverySource: makeDirectTypeProxy(expr.dig(config, ["documentBackfillConfig", "useRecoverySource"], false)),
            documentBackfillPositionGapStopword: makeStringTypeProxy(expr.dig(config, ["documentBackfillConfig", "positionGapStopword"], expr.literal("a"))),
            documentBackfillDocTransformerConfigBase64: makeStringTypeProxy(expr.dig(config, ["documentBackfillConfig", "docTransformerConfigBase64"], expr.literal(""))),
            documentBackfillDocTransformerConfig: makeYamlJsonLiteralProxy(expr.dig(config, ["documentBackfillConfig", "docTransformerConfig"], expr.literal(""))),
            documentBackfillDocTransformerConfigFile: makeStringTypeProxy(expr.dig(config, ["documentBackfillConfig", "docTransformerConfigFile"], expr.literal(""))),
            documentBackfillFileSourceVolumes: makeYamlJsonLiteralProxy(expr.dig(config, ["documentBackfillConfig", "fileSourceVolumes"], expr.literal([]))),
            documentBackfillFileSourceVolumeMounts: makeYamlJsonLiteralProxy(expr.dig(config, ["documentBackfillConfig", "fileSourceVolumeMounts"], expr.literal([]))),
            documentBackfillDocumentsPerBulkRequest: makeDirectTypeProxy(expr.dig(config, ["documentBackfillConfig", "documentsPerBulkRequest"], 0x7fffffff)),
            documentBackfillDocumentsSizePerBulkRequest: makeDirectTypeProxy(expr.dig(config, ["documentBackfillConfig", "documentsSizePerBulkRequest"], 10 * 1024 * 1024)),
            documentBackfillInitialLeaseDuration: makeStringTypeProxy(expr.dig(config, ["documentBackfillConfig", "initialLeaseDuration"], expr.literal("PT1H"))),
            documentBackfillMaxConnections: makeDirectTypeProxy(expr.dig(config, ["documentBackfillConfig", "maxConnections"], 10)),
            documentBackfillMaxShardSizeBytes: makeDirectTypeProxy(expr.dig(config, ["documentBackfillConfig", "maxShardSizeBytes"], 80 * 1024 * 1024 * 1024)),
            documentBackfillOtelCollectorEndpoint: makeStringTypeProxy(expr.dig(config, ["documentBackfillConfig", "otelCollectorEndpoint"], expr.literal(""))),
            documentBackfillServerGeneratedIds: makeStringTypeProxy(expr.dig(config, ["documentBackfillConfig", "serverGeneratedIds"], expr.literal("AUTO"))),
            documentBackfillEmitDocType: makeStringTypeProxy(expr.dig(config, ["documentBackfillConfig", "emitDocType"], expr.literal("AUTO"))),
            documentBackfillAllowedDocExceptionTypes: makeDirectTypeProxy(expr.dig(config, ["documentBackfillConfig", "allowedDocExceptionTypes"], expr.literal([]))),
            documentBackfillCoordinatorRetryMaxRetries: makeDirectTypeProxy(expr.dig(config, ["documentBackfillConfig", "coordinatorRetryMaxRetries"], 7)),
            documentBackfillCoordinatorRetryInitialDelayMs: makeDirectTypeProxy(expr.dig(config, ["documentBackfillConfig", "coordinatorRetryInitialDelayMs"], 1000)),
            documentBackfillCoordinatorRetryMaxDelayMs: makeDirectTypeProxy(expr.dig(config, ["documentBackfillConfig", "coordinatorRetryMaxDelayMs"], 64000)),
        },
    };
}

function makeTrafficReplayManifest(
    name: BaseExpression<string>,
    dependsOn: BaseExpression<Serialized<string[]>>,
    replayerOptions: BaseExpression<Serialized<z.infer<typeof ARGO_REPLAYER_OPTIONS>>>,
    sourceLabel: BaseExpression<string>,
    targetLabel: BaseExpression<string>,
) {
    const opts = expr.deserializeRecord(replayerOptions);
    const workflowSpecFields = expr.makeDict({
        dependsOn: expr.deserializeRecord(dependsOn),
        jvmArgs: expr.dig(opts, ["jvmArgs"], expr.literal("")),
        loggingConfigurationOverrideConfigMap: expr.dig(
            opts,
            ["loggingConfigurationOverrideConfigMap"],
            expr.literal("")
        ),
        podReplicas: expr.dig(opts, ["podReplicas"], 1),
        resources: expr.get(opts, "resources"),
        fileSourceVolumes: expr.dig(opts, ["fileSourceVolumes"], expr.literal([])),
        fileSourceVolumeMounts: expr.dig(opts, ["fileSourceVolumeMounts"], expr.literal([])),
    });
    return {
        apiVersion: CRD_API_VERSION,
        kind: "TrafficReplay",
        metadata: {
            name: makeStringTypeProxy(name),
            labels: {
                [WORKFLOW_LABEL]: makeStringTypeProxy(expr.getWorkflowValue("name")),
                [RUN_NUMBER_LABEL]: MIGRATION_RUN_NUMBER_VALUE,
                [SOURCE_LABEL]: makeStringTypeProxy(sourceLabel),
                [TARGET_LABEL]: makeStringTypeProxy(targetLabel),
                [TASK_LABEL]: "trafficReplayer",
            }
        },
        spec: makeDirectTypeProxy(expr.mergeDicts(
            workflowSpecFields,
            expr.omit(opts, ...ARGO_REPLAYER_WORKFLOW_OPTION_KEYS)
        )),
    };
}

export const ResourceManagement = WorkflowBuilder.create({
    k8sResourceName: "resource-management",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)

    // ── Root resource mutations ──────────────────────────────────────────

    .addTemplate("upsertKafkaClusterResource", t => t
        .addRequiredInput("kafkaClusterConfig", typeToken<z.infer<typeof NAMED_KAFKA_CLUSTER_CONFIG>>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeKafkaClusterManifest(b.inputs.kafkaClusterConfig)
            }))
        .addJsonPathOutput("currentConfigChecksum", "{.status.configChecksum}", typeToken<string>())
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    .addTemplate("upsertCapturedTrafficResource", t => t
        .addRequiredInput("topicCrName", typeToken<string>())
        .addRequiredInput("kafkaClusterName", typeToken<string>())
        .addRequiredInput("kafkaTopicName", typeToken<string>())
        .addRequiredInput("sourceLabel", typeToken<string>())
        .addRequiredInput("partitions", typeToken<number>())
        .addRequiredInput("replicas", typeToken<number>())
        .addRequiredInput("topicConfig", typeToken<Serialized<Record<string, any>>>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeCapturedTrafficManifest(
                    b.inputs.topicCrName,
                    b.inputs.kafkaClusterName,
                    b.inputs.kafkaTopicName,
                    b.inputs.sourceLabel,
                    b.inputs.partitions,
                    b.inputs.replicas,
                    b.inputs.topicConfig
                )
            }))
        .addJsonPathOutput("currentConfigChecksum", "{.status.configChecksum}", typeToken<string>())
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    .addTemplate("upsertCaptureProxyResource", t => t
        .addRequiredInput("proxyConfig", typeToken<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>())
        .addRequiredInput("proxyName", typeToken<string>())
        .addRequiredInput("topicCrName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeCaptureProxyManifest(b.inputs.proxyConfig, b.inputs.proxyName, b.inputs.topicCrName)
            }))
        .addJsonPathOutput("currentConfigChecksum", "{.status.configChecksum}", typeToken<string>())
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    .addTemplate("upsertDataSnapshotResource", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("snapshotItemConfig", typeToken<z.infer<typeof PER_SOURCE_CREATE_SNAPSHOTS_CONFIG>>())
        .addRequiredInput("sourceLabel", typeToken<string>())
        .addResourceTask(b => {
            const snapshotItemConfig = expr.deserializeRecord(b.inputs.snapshotItemConfig);
            const snapshotOptions = expr.get(snapshotItemConfig, "config");

            return b.setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: "DataSnapshot",
                    metadata: {
                        name: b.inputs.resourceName,
                        labels: {
                            [WORKFLOW_LABEL]: makeStringTypeProxy(expr.getWorkflowValue("name")),
                            [RUN_NUMBER_LABEL]: MIGRATION_RUN_NUMBER_VALUE,
                            [SOURCE_LABEL]: makeStringTypeProxy(b.inputs.sourceLabel),
                            [SNAPSHOT_LABEL]: makeStringTypeProxy(expr.get(snapshotItemConfig, "label")),
                        }
                    },
                    spec: {
                        snapshotPrefix: makeStringTypeProxy(expr.get(snapshotItemConfig, "snapshotPrefix")),
                        indexAllowlist: makeDirectTypeProxy(
                            expr.dig(snapshotOptions, ["indexAllowlist"], expr.literal([])) as any
                        ),
                        maxSnapshotRateMbPerNode: makeDirectTypeProxy(
                            expr.dig(snapshotOptions, ["maxSnapshotRateMbPerNode"], 0)
                        ),
                        jvmArgs: makeStringTypeProxy(expr.dig(snapshotOptions, ["jvmArgs"], expr.literal(""))),
                        loggingConfigurationOverrideConfigMap: makeStringTypeProxy(
                            expr.dig(snapshotOptions, ["loggingConfigurationOverrideConfigMap"], expr.literal(""))
                        )
                    }
                }
            });
        })
        .addJsonPathOutput("currentConfigChecksum", "{.status.configChecksum}", typeToken<string>())
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    .addTemplate("upsertSnapshotMigrationResource", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("snapshotMigrationConfig", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeSnapshotMigrationManifest(
                    b.inputs.resourceName,
                    b.inputs.snapshotMigrationConfig
                )
            }))
        .addJsonPathOutput("currentConfigChecksum", "{.status.configChecksum}", typeToken<string>())
        .addJsonPathOutput("resourceCreationTimestamp", "{.metadata.creationTimestamp}", typeToken<string>())
        // Emit the real apiserver-assigned UID so downstream owned resources (RFS coordinator
        // Secret/Service/StatefulSet) can set valid ownerReferences. Without this, callers
        // would propagate a placeholder UID (e.g. "imported" for BYOS/imported-cluster paths)
        // and Kubernetes GC would delete the owned resources within ~1s as orphaned children.
        .addJsonPathOutput("resourceUid", "{.metadata.uid}", typeToken<string>())
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    .addTemplate("upsertTrafficReplayResource", t => t
        .addRequiredInput("name", typeToken<string>())
        .addRequiredInput("dependsOn", typeToken<string[]>())
        .addRequiredInput("replayerOptions", typeToken<z.infer<typeof ARGO_REPLAYER_OPTIONS>>())
        .addRequiredInput("sourceLabel", typeToken<string>())
        .addRequiredInput("targetLabel", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "apply",
                setOwnerReference: false,
                manifest: makeTrafficReplayManifest(
                    b.inputs.name,
                    b.inputs.dependsOn,
                    b.inputs.replayerOptions,
                    b.inputs.sourceLabel,
                    b.inputs.targetLabel
                )
            }))
        .addJsonPathOutput("currentConfigChecksum", "{.status.configChecksum}", typeToken<string>())
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    // ── Approval gate primitives ─────────────────────────────────────────

    .addTemplate("waitForUserApproval", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addWaitForExistingResource(b => b
            .setDefinition({
                resource: {
                    apiVersion: CRD_API_VERSION,
                    kind: "ApprovalGate",
                    name: b.inputs.resourceName
                },
                conditions: {successCondition: "status.phase == Approved"}
            })
            .addRetryParameters(K8S_USER_APPROVAL_WAIT_RETRY_STRATEGY)
        )
    )

    .addTemplate("patchApprovalAnnotation", t => t
        .addRequiredInput("resourceApiVersion", typeToken<string>())
        .addRequiredInput("resourceKind", typeToken<string>())
        .addRequiredInput("resourceName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "patch",
                flags: ["--type", "merge"],
                manifest: {
                    apiVersion: makeStringTypeProxy(b.inputs.resourceApiVersion),
                    kind: makeStringTypeProxy(b.inputs.resourceKind),
                    metadata: {
                        name: b.inputs.resourceName,
                        annotations: {
                            [APPROVED_DURING_RUN_ANNOTATION]: MIGRATION_RUN_NUMBER_VALUE
                        }
                    }
                }
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    .addTemplate("patchApprovalGatePhase", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("phase", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "patch",
                flags: ["--type", "merge", "--subresource=status"],
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: "ApprovalGate",
                    metadata: { name: "{{inputs.parameters.resourceName}}" },
                    status: { phase: "{{inputs.parameters.phase}}" }
                }
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    .addTemplate("patchKafkaClusterPending", t => buildPatchStatusTemplate(t, "KafkaCluster", {}))
    .addTemplate("patchCapturedTrafficPending", t => buildPatchStatusTemplate(t, "CapturedTraffic", {}))
    .addTemplate("patchCaptureProxyPending", t => buildPatchStatusTemplate(t, "CaptureProxy", {}))
    .addTemplate("patchDataSnapshotPending", t => buildPatchStatusTemplate(t, "DataSnapshot", {}))
    .addTemplate("patchSnapshotMigrationPending", t => buildPatchStatusTemplate(t, "SnapshotMigration", {}))
    .addTemplate("patchTrafficReplayPending", t => buildPatchStatusTemplate(t, "TrafficReplay", {}))

    // ── Root reconcile wrappers ──────────────────────────────────────────

    .addTemplate("reconcileKafkaClusterResource", t => t
        .addRequiredInput("kafkaClusterConfig", typeToken<z.infer<typeof NAMED_KAFKA_CLUSTER_CONFIG>>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("retryGateName", typeToken<string>())
        .addOptionalInput("retryGroupName_view", c => "Apply")

        .addSteps(b => b
            .addStep("tryApply", INTERNAL, "upsertKafkaClusterResource", c =>
                c.register({
                    kafkaClusterConfig: b.inputs.kafkaClusterConfig,
                }),
                {continueOn: {failed: true}}
            )
            .addStep("markPending", INTERNAL, "patchKafkaClusterPending", c =>
                c.register({
                    resourceName: expr.jsonPathStrict(b.inputs.kafkaClusterConfig, "name"),
                    phase: expr.literal("Pending"),
                }),
                {when: c => ({templateExp: expr.and(
                    expr.equals(c.tryApply.status, "Succeeded"),
                    expr.not(expr.equals(c.tryApply.outputs.currentConfigChecksum, b.inputs.configChecksum))
                )})}
            )
            .addStep("waitForFix", INTERNAL, "waitForUserApproval", c =>
                c.register({
                    resourceName: b.inputs.retryGateName,
                }),
                {when: c => ({templateExp: expr.equals(c.tryApply.status, "Failed")})}
            )
            .addStep("patchApproval", INTERNAL, "patchApprovalAnnotation", c =>
                c.register({
                    resourceApiVersion: expr.literal(CRD_API_VERSION),
                    resourceKind: expr.literal("KafkaCluster"),
                    resourceName: expr.jsonPathStrict(b.inputs.kafkaClusterConfig, "name"),
                }),
                {when: c => ({templateExp: expr.equals(c.waitForFix.status, "Succeeded")})}
            )
            .addStep("resetGate", INTERNAL, "patchApprovalGatePhase", c =>
                c.register({
                    resourceName: b.inputs.retryGateName,
                    phase: expr.literal("Pending"),
                }),
                {when: c => ({templateExp: expr.equals(c.patchApproval.status, "Succeeded")})}
            )
            .addStepToSelf("retryLoop", c =>
                c.register({
                    kafkaClusterConfig: b.inputs.kafkaClusterConfig,
                    configChecksum: b.inputs.configChecksum,
                    retryGateName: b.inputs.retryGateName,
                    retryGroupName_view: b.inputs.retryGroupName_view,
                }),
                {when: c => ({templateExp: expr.equals(c.resetGate.status, "Succeeded")})}
            )
        )
        .addExpressionOutput("currentConfigChecksum", c =>
            expr.ternary(
                expr.equals(c.steps.tryApply.status, "Succeeded"),
                c.steps.tryApply.outputs.currentConfigChecksum,
                expr.literal("")
            ))
    )

    .addTemplate("reconcileCapturedTrafficResource", t => t
        .addRequiredInput("topicCrName", typeToken<string>())
        .addRequiredInput("kafkaClusterName", typeToken<string>())
        .addRequiredInput("kafkaTopicName", typeToken<string>())
        .addRequiredInput("partitions", typeToken<number>())
        .addRequiredInput("replicas", typeToken<number>())
        .addRequiredInput("topicConfig", typeToken<Serialized<Record<string, any>>>())
        .addRequiredInput("sourceLabel", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("retryGateName", typeToken<string>())
        .addOptionalInput("retryGroupName_view", c => "Apply")

        .addSteps(b => b
            .addStep("tryApply", INTERNAL, "upsertCapturedTrafficResource", c =>
                c.register({
                    topicCrName: b.inputs.topicCrName,
                    kafkaClusterName: b.inputs.kafkaClusterName,
                    kafkaTopicName: b.inputs.kafkaTopicName,
                    sourceLabel: b.inputs.sourceLabel,
                    partitions: b.inputs.partitions,
                    replicas: b.inputs.replicas,
                    topicConfig: b.inputs.topicConfig,
                }),
                {continueOn: {failed: true}}
            )
            .addStep("markPending", INTERNAL, "patchCapturedTrafficPending", c =>
                c.register({
                    resourceName: b.inputs.topicCrName,
                    phase: expr.literal("Pending"),
                }),
                {when: c => ({templateExp: expr.and(
                    expr.equals(c.tryApply.status, "Succeeded"),
                    expr.not(expr.equals(c.tryApply.outputs.currentConfigChecksum, b.inputs.configChecksum))
                )})}
            )
            .addStep("waitForFix", INTERNAL, "waitForUserApproval", c =>
                c.register({
                    resourceName: b.inputs.retryGateName,
                }),
                {when: c => ({templateExp: expr.equals(c.tryApply.status, "Failed")})}
            )
            .addStep("patchApproval", INTERNAL, "patchApprovalAnnotation", c =>
                c.register({
                    resourceApiVersion: expr.literal(CRD_API_VERSION),
                    resourceKind: expr.literal("CapturedTraffic"),
                    resourceName: b.inputs.topicCrName,
                }),
                {when: c => ({templateExp: expr.equals(c.waitForFix.status, "Succeeded")})}
            )
            .addStep("resetGate", INTERNAL, "patchApprovalGatePhase", c =>
                c.register({
                    resourceName: b.inputs.retryGateName,
                    phase: expr.literal("Pending"),
                }),
                {when: c => ({templateExp: expr.equals(c.patchApproval.status, "Succeeded")})}
            )
            .addStepToSelf("retryLoop", c =>
                c.register({
                    topicCrName: b.inputs.topicCrName,
                    kafkaClusterName: b.inputs.kafkaClusterName,
                    kafkaTopicName: b.inputs.kafkaTopicName,
                    sourceLabel: b.inputs.sourceLabel,
                    partitions: b.inputs.partitions,
                    replicas: b.inputs.replicas,
                    topicConfig: b.inputs.topicConfig,
                    configChecksum: b.inputs.configChecksum,
                    retryGateName: b.inputs.retryGateName,
                    retryGroupName_view: b.inputs.retryGroupName_view,
                }),
                {when: c => ({templateExp: expr.equals(c.resetGate.status, "Succeeded")})}
            )
        )
        .addExpressionOutput("currentConfigChecksum", c =>
            expr.ternary(
                expr.equals(c.steps.tryApply.status, "Succeeded"),
                c.steps.tryApply.outputs.currentConfigChecksum,
                expr.literal("")
            ))
    )

    .addTemplate("reconcileCaptureProxyResource", t => t
        .addRequiredInput("proxyConfig", typeToken<z.infer<typeof DENORMALIZED_PROXY_CONFIG>>())
        .addRequiredInput("proxyName", typeToken<string>())
        .addRequiredInput("topicCrName", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("retryGateName", typeToken<string>())
        .addOptionalInput("retryGroupName_view", c => "Apply")

        .addSteps(b => b
            .addStep("tryApply", INTERNAL, "upsertCaptureProxyResource", c =>
                c.register({
                    proxyConfig: b.inputs.proxyConfig,
                    proxyName: b.inputs.proxyName,
                    topicCrName: b.inputs.topicCrName,
                }),
                {continueOn: {failed: true}}
            )
            .addStep("markPending", INTERNAL, "patchCaptureProxyPending", c =>
                c.register({
                    resourceName: b.inputs.proxyName,
                    phase: expr.literal("Pending"),
                }),
                {when: c => ({templateExp: expr.and(
                    expr.equals(c.tryApply.status, "Succeeded"),
                    expr.not(expr.equals(c.tryApply.outputs.currentConfigChecksum, b.inputs.configChecksum))
                )})}
            )
            .addStep("waitForFix", INTERNAL, "waitForUserApproval", c =>
                c.register({
                    resourceName: b.inputs.retryGateName,
                }),
                {when: c => ({templateExp: expr.equals(c.tryApply.status, "Failed")})}
            )
            .addStep("patchApproval", INTERNAL, "patchApprovalAnnotation", c =>
                c.register({
                    resourceApiVersion: expr.literal(CRD_API_VERSION),
                    resourceKind: expr.literal("CaptureProxy"),
                    resourceName: b.inputs.proxyName,
                }),
                {when: c => ({templateExp: expr.equals(c.waitForFix.status, "Succeeded")})}
            )
            .addStep("resetGate", INTERNAL, "patchApprovalGatePhase", c =>
                c.register({
                    resourceName: b.inputs.retryGateName,
                    phase: expr.literal("Pending"),
                }),
                {when: c => ({templateExp: expr.equals(c.patchApproval.status, "Succeeded")})}
            )
            .addStepToSelf("retryLoop", c =>
                c.register({
                    proxyConfig: b.inputs.proxyConfig,
                    proxyName: b.inputs.proxyName,
                    topicCrName: b.inputs.topicCrName,
                    configChecksum: b.inputs.configChecksum,
                    retryGateName: b.inputs.retryGateName,
                    retryGroupName_view: b.inputs.retryGroupName_view,
                }),
                {when: c => ({templateExp: expr.equals(c.resetGate.status, "Succeeded")})}
            )
        )
        .addExpressionOutput("currentConfigChecksum", c =>
            expr.ternary(
                expr.equals(c.steps.tryApply.status, "Succeeded"),
                c.steps.tryApply.outputs.currentConfigChecksum,
                expr.literal("")
            ))
    )

    .addTemplate("reconcileDataSnapshotResource", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("snapshotItemConfig", typeToken<z.infer<typeof PER_SOURCE_CREATE_SNAPSHOTS_CONFIG>>())
        .addRequiredInput("sourceLabel", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addOptionalInput("retryGroupName_view", c => "Apply")

        .addSteps(b => b
            .addStep("tryApply", INTERNAL, "upsertDataSnapshotResource", c =>
                c.register({
                    resourceName: b.inputs.resourceName,
                    snapshotItemConfig: b.inputs.snapshotItemConfig,
                    sourceLabel: b.inputs.sourceLabel,
                }),
                {continueOn: {failed: true}}
            )
            .addStep("markPending", INTERNAL, "patchDataSnapshotPending", c =>
                c.register({
                    resourceName: b.inputs.resourceName,
                    phase: expr.literal("Pending"),
                }),
                {when: c => ({templateExp: expr.and(
                    expr.equals(c.tryApply.status, "Succeeded"),
                    expr.not(expr.equals(c.tryApply.outputs.currentConfigChecksum, b.inputs.configChecksum))
                )})}
            )
        )
        .addExpressionOutput("currentConfigChecksum", c =>
            expr.ternary(
                expr.equals(c.steps.tryApply.status, "Succeeded"),
                c.steps.tryApply.outputs.currentConfigChecksum,
                expr.literal("")
            ))
    )

    .addTemplate("reconcileSnapshotMigrationResource", t => t
        .addRequiredInput("snapshotMigrationConfig", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>())
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("retryGateName", typeToken<string>())
        .addOptionalInput("retryGroupName_view", c => "Apply")

        .addSteps(b => b
            .addStep("tryApply", INTERNAL, "upsertSnapshotMigrationResource", c =>
                c.register({
                    resourceName: b.inputs.resourceName,
                    snapshotMigrationConfig: b.inputs.snapshotMigrationConfig,
                }),
                {continueOn: {failed: true}}
            )
            .addStep("markPending", INTERNAL, "patchSnapshotMigrationPending", c =>
                c.register({
                    resourceName: b.inputs.resourceName,
                    phase: expr.literal("Pending"),
                }),
                {when: c => ({templateExp: expr.and(
                    expr.equals(c.tryApply.status, "Succeeded"),
                    expr.not(expr.equals(c.tryApply.outputs.currentConfigChecksum, b.inputs.configChecksum))
                )})}
            )
            .addStep("waitForFix", INTERNAL, "waitForUserApproval", c =>
                c.register({
                    resourceName: b.inputs.retryGateName,
                }),
                {when: c => ({templateExp: expr.equals(c.tryApply.status, "Failed")})}
            )
            .addStep("patchApproval", INTERNAL, "patchApprovalAnnotation", c =>
                c.register({
                    resourceApiVersion: expr.literal(CRD_API_VERSION),
                    resourceKind: expr.literal("SnapshotMigration"),
                    resourceName: b.inputs.resourceName,
                }),
                {when: c => ({templateExp: expr.equals(c.waitForFix.status, "Succeeded")})}
            )
            .addStep("resetGate", INTERNAL, "patchApprovalGatePhase", c =>
                c.register({
                    resourceName: b.inputs.retryGateName,
                    phase: expr.literal("Pending"),
                }),
                {when: c => ({templateExp: expr.equals(c.patchApproval.status, "Succeeded")})}
            )
            .addStepToSelf("retryLoop", c =>
                c.register({
                    snapshotMigrationConfig: b.inputs.snapshotMigrationConfig,
                    resourceName: b.inputs.resourceName,
                    configChecksum: b.inputs.configChecksum,
                    retryGateName: b.inputs.retryGateName,
                    retryGroupName_view: b.inputs.retryGroupName_view,
                }),
                {when: c => ({templateExp: expr.equals(c.resetGate.status, "Succeeded")})}
            )
        )
        .addExpressionOutput("currentConfigChecksum", c =>
            expr.ternary(
                expr.equals(c.steps.tryApply.status, "Succeeded"),
                c.steps.tryApply.outputs.currentConfigChecksum,
                expr.literal("")
            ))
        .addExpressionOutput("resourceCreationTimestamp", c =>
            expr.ternary(
                expr.equals(c.steps.tryApply.status, "Succeeded"),
                c.steps.tryApply.outputs.resourceCreationTimestamp,
                expr.literal("")
            ))
        .addExpressionOutput("resourceUid", c =>
            expr.ternary(
                expr.equals(c.steps.tryApply.status, "Succeeded"),
                c.steps.tryApply.outputs.resourceUid,
                expr.literal("")
            ))
    )

    .addTemplate("reconcileTrafficReplayResource", t => t
        .addRequiredInput("name", typeToken<string>())
        .addRequiredInput("dependsOn", typeToken<string[]>())
        .addRequiredInput("replayerOptions", typeToken<z.infer<typeof ARGO_REPLAYER_OPTIONS>>())
        .addRequiredInput("sourceLabel", typeToken<string>())
        .addRequiredInput("targetLabel", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("retryGateName", typeToken<string>())
        .addOptionalInput("retryGroupName_view", c => "Apply")

        .addSteps(b => b
            .addStep("tryApply", INTERNAL, "upsertTrafficReplayResource", c =>
                c.register({
                    name: b.inputs.name,
                    dependsOn: b.inputs.dependsOn,
                    replayerOptions: b.inputs.replayerOptions,
                    sourceLabel: b.inputs.sourceLabel,
                    targetLabel: b.inputs.targetLabel,
                }),
                {continueOn: {failed: true}}
            )
            .addStep("markPending", INTERNAL, "patchTrafficReplayPending", c =>
                c.register({
                    resourceName: b.inputs.name,
                    phase: expr.literal("Pending"),
                }),
                {when: c => ({templateExp: expr.and(
                    expr.equals(c.tryApply.status, "Succeeded"),
                    expr.not(expr.equals(c.tryApply.outputs.currentConfigChecksum, b.inputs.configChecksum))
                )})}
            )
            .addStep("waitForFix", INTERNAL, "waitForUserApproval", c =>
                c.register({
                    resourceName: b.inputs.retryGateName,
                }),
                {when: c => ({templateExp: expr.equals(c.tryApply.status, "Failed")})}
            )
            .addStep("patchApproval", INTERNAL, "patchApprovalAnnotation", c =>
                c.register({
                    resourceApiVersion: expr.literal(CRD_API_VERSION),
                    resourceKind: expr.literal("TrafficReplay"),
                    resourceName: b.inputs.name,
                }),
                {when: c => ({templateExp: expr.equals(c.waitForFix.status, "Succeeded")})}
            )
            .addStep("resetGate", INTERNAL, "patchApprovalGatePhase", c =>
                c.register({
                    resourceName: b.inputs.retryGateName,
                    phase: expr.literal("Pending"),
                }),
                {when: c => ({templateExp: expr.equals(c.patchApproval.status, "Succeeded")})}
            )
            .addStepToSelf("retryLoop", c =>
                c.register({
                    name: b.inputs.name,
                    dependsOn: b.inputs.dependsOn,
                    replayerOptions: b.inputs.replayerOptions,
                    sourceLabel: b.inputs.sourceLabel,
                    targetLabel: b.inputs.targetLabel,
                    configChecksum: b.inputs.configChecksum,
                    retryGateName: b.inputs.retryGateName,
                    retryGroupName_view: b.inputs.retryGroupName_view,
                }),
                {when: c => ({templateExp: expr.equals(c.resetGate.status, "Succeeded")})}
            )
        )
        .addExpressionOutput("currentConfigChecksum", c =>
            expr.ternary(
                expr.equals(c.steps.tryApply.status, "Succeeded"),
                c.steps.tryApply.outputs.currentConfigChecksum,
                expr.literal("")
            ))
    )

    // ── Status updates ───────────────────────────────────────────────────

    .addTemplate("patchKafkaClusterReady", t => buildPatchStatusTemplate(t, "KafkaCluster", {
        configChecksum: ""
    }))
    .addTemplate("patchCapturedTrafficReady", t => buildPatchStatusTemplate(t, "CapturedTraffic", {
        configChecksum: "",
        checksumForSnapshot: "",
        checksumForReplayer: ""
    }))
    .addTemplate("patchCapturedTrafficError", t => buildPatchStatusTemplate(t, "CapturedTraffic", {}))
    .addTemplate("patchCaptureProxyReady", t => buildPatchStatusTemplate(t, "CaptureProxy", {
        configChecksum: "",
        checksumForSnapshot: "",
        checksumForReplayer: "",
        serviceEndpoint: "",
        loadBalancerEndpoint: "",
    }))
    .addTemplate("patchCaptureProxyError", t => buildPatchStatusTemplate(t, "CaptureProxy", {}))
    .addTemplate("patchDataSnapshotCompleted", t => buildPatchStatusTemplate(t, "DataSnapshot", {
        snapshotName: "",
        configChecksum: "",
        checksumForSnapshotMigration: ""
    }))
    .addTemplate("patchSnapshotMigrationCompleted", t => buildPatchStatusTemplate(t, "SnapshotMigration", {
        configChecksum: "",
        checksumForReplayer: ""
    }))
    .addTemplate("patchSnapshotMigrationOutputEvaluate", t =>
        buildPatchOutputTemplate(t, "SnapshotMigration", "metadataEvaluate"))
    .addTemplate("patchSnapshotMigrationOutputMigrate", t =>
        buildPatchOutputTemplate(t, "SnapshotMigration", "metadataMigrate"))
    .addTemplate("patchTrafficReplayReady", t => buildPatchStatusTemplate(t, "TrafficReplay", {
        configChecksum: ""
    }))


    // ── Wait templates (resource get with retry) ─────────────────────────
    // Leaf Kubernetes/Strimzi/cert-manager waits use bounded infra retries.
    // Waits on our own migration status resources can run much longer in a
    // single attempt because another workflow branch is responsible for moving
    // the CR to Ready/Completed/Error. Their retry limit only protects against
    // wait pod eviction or transient API failures.

    .addTemplate("waitForKafkaClusterCreated", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addWaitForNewResource(b => b
            .setDefinition({
                resourceKindAndName: expr.concat(expr.literal("kafka/"), b.inputs.resourceName),
                waitForCreation: {
                    kubectlImage: b.inputs.imageMigrationConsoleLocation,
                    kubectlImagePullPolicy: b.inputs.imageMigrationConsolePullPolicy,
                    maxDurationSeconds: K8S_INFRA_READY_TIMEOUT_SECONDS,
                    maxKubeWaitDuration: 60,
                    retryLimit: 15,
                    retryInitialBackoffDuration: 10,
                    retryFactor: 2,
                }
            })
        )
    )


    // Argo resource-template conditions are Kubernetes label selectors evaluated
    // against JSON paths. They cannot search status.conditions by type, so
    // `status.conditions.0.type == Ready` breaks whenever Strimzi emits Warning
    // conditions before Ready. Use a tiny kubectl+jq loop for exact Strimzi
    // Ready semantics while still keeping this as a ResourceManagement wait.
    .addTemplate("waitForKafkaClusterReadyResource", t => t
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
                ...workflowScriptRootEnvVars(t.inputs.workflowParameters.workflowScriptsRoot)
            })
            .addArgs([workflowScriptCommand("waitForKafkaClusterReady.sh")])
            .addActiveDeadlineSeconds(() => KAFKA_READY_WAIT_ACTIVE_DEADLINE_SECONDS)
        )
        .addRetryParameters(KAFKA_READY_WAIT_POD_RETRY_STRATEGY)
    )

    .addTemplate("waitForKafkaTopicReady", t => t
        .addRequiredInput("topicName", typeToken<string>())
        .addWaitForExistingResource(b => b
            .setDefinition({
                resource: {
                    apiVersion: "kafka.strimzi.io/v1",
                    kind: "KafkaTopic",
                    name: b.inputs.topicName
                },
                conditions: {
                    successCondition: "status.conditions.0.type == Ready, status.conditions.0.status == True"
                }
            })
            .addRetryParameters(K8S_INFRA_READY_RETRY_STRATEGY)
        )
    )


    .addTemplate("waitForSecretKey", t => t
        .addRequiredInput("secretName", typeToken<string>())
        .addRequiredInput("secretKey", typeToken<string>())
        .addWaitForExistingResource(b => b
            .setDefinition({
                resource: {
                    apiVersion: "v1",
                    kind: "Secret",
                    name: b.inputs.secretName
                },
                conditions: {
                    successCondition: expr.concat(expr.literal("data."), b.inputs.secretKey)
                }
            })
            .addRetryParameters(K8S_SECRET_READY_RETRY_STRATEGY)
        )
    )


    .addTemplate("readKafkaConnectionProfile", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "get",
                manifest: {
                    apiVersion: "kafka.strimzi.io/v1",
                    kind: "Kafka",
                    metadata: {name: b.inputs.resourceName}
                }
            })
            .addJsonPathOutput("bootstrapServers", "{.status.listeners[0].bootstrapServers}", typeToken<string>())
            .addJsonPathOutput("listenerName", "{.status.listeners[0].name}", typeToken<string>())
            .addJsonPathOutput("authType", "{.spec.kafka.listeners[0].authentication.type}", typeToken<string>()))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    .addTemplate("waitIndefinitelyForCapturedTraffic", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("checksumField", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addWaitForExistingResource(b => b
            .setDefinition({
                resource: {
                    apiVersion: CRD_API_VERSION,
                    kind: "CapturedTraffic",
                    name: b.inputs.resourceName
                },
                conditions: {
                    successCondition: expr.concat(
                        expr.literal("status.phase == Ready, status."),
                        b.inputs.checksumField,
                        expr.literal(" == "),
                        b.inputs.configChecksum
                    ),
                    failureCondition: "status.phase == Error"
                }
            })
            .addRetryParameters(K8S_INDEFINITE_RESOURCE_WAIT_RETRY_STRATEGY)
        )
    )


    .addTemplate("waitIndefinitelyForCaptureProxy", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("checksumField", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addWaitForExistingResource(b => b
            .setDefinition({
                resource: {
                    apiVersion: CRD_API_VERSION,
                    kind: "CaptureProxy",
                    name: b.inputs.resourceName
                },
                conditions: {
                    successCondition: expr.concat(
                        expr.literal("status.phase == Ready, status."),
                        b.inputs.checksumField,
                        expr.literal(" == "),
                        b.inputs.configChecksum
                    ),
                    failureCondition: "status.phase == Error"
                }
            })
            .addRetryParameters(K8S_INDEFINITE_RESOURCE_WAIT_RETRY_STRATEGY)
        )
    )


    .addTemplate("waitIndefinitelyForDataSnapshot", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("checksumField", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addWaitForExistingResource(b => b
            .setDefinition({
                resource: {
                    apiVersion: CRD_API_VERSION,
                    kind: "DataSnapshot",
                    name: b.inputs.resourceName
                },
                conditions: {
                    successCondition: expr.concat(
                        expr.literal("status.phase == Completed, status."),
                        b.inputs.checksumField,
                        expr.literal(" == "),
                        b.inputs.configChecksum
                    ),
                    failureCondition: "status.phase == Error"
                }
            })
            .addRetryParameters(K8S_INDEFINITE_RESOURCE_WAIT_RETRY_STRATEGY)
        )
    )


    .addTemplate("waitIndefinitelyForSnapshotMigration", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("checksumField", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addWaitForExistingResource(b => b
            .setDefinition({
                resource: {
                    apiVersion: CRD_API_VERSION,
                    kind: "SnapshotMigration",
                    name: b.inputs.resourceName
                },
                conditions: {
                    successCondition: expr.concat(
                        expr.literal("status.phase == Completed, status."),
                        b.inputs.checksumField,
                        expr.literal(" == "),
                        b.inputs.configChecksum
                    ),
                    failureCondition: "status.phase == Error"
                }
            })
            .addRetryParameters(K8S_INDEFINITE_RESOURCE_WAIT_RETRY_STRATEGY)
        )
    )


    .addTemplate("readDataSnapshotName", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "get",
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: "DataSnapshot",
                    metadata: {name: b.inputs.resourceName}
                }
            })
            .addJsonPathOutput("snapshotName", "{.status.snapshotName}", typeToken<string>()))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    .addTemplate("readResourcePhase", t => t
        .addRequiredInput("resourceKind", typeToken<string>())
        .addRequiredInput("resourceName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "get",
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: makeStringTypeProxy(b.inputs.resourceKind),
                    metadata: { name: b.inputs.resourceName }
                }
            })
            .addJsonPathOutput("phase", "{.status.phase}", typeToken<string>())
            .addJsonPathOutput("configChecksum", "{.status.configChecksum}", typeToken<string>()))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    .addTemplate("readResourceUid", t => t
        .addRequiredInput("resourceKind", typeToken<string>())
        .addRequiredInput("resourceName", typeToken<string>())
        .addResourceTask(b => b
            .setDefinition({
                action: "get",
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: makeStringTypeProxy(b.inputs.resourceKind),
                    metadata: { name: b.inputs.resourceName }
                }
            })
            .addJsonPathOutput("uid", "{.metadata.uid}", typeToken<string>()))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )

    .getFullScope();
