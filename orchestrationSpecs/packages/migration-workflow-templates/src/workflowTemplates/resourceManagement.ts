import {
    AllowLiteralOrExpression,
    expr,
    INTERNAL,
    InputParamDef,
    InputParametersRecord,
    makeStringTypeProxy,
    selectInputsForRegister,
    TemplateBuilder,
    typeToken,
    WorkflowAndTemplatesScope,
    WorkflowBuilder
} from '@opensearch-migrations/argo-workflow-builders';
import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {K8S_RESOURCE_RETRY_STRATEGY} from "./commonUtils/resourceRetryStrategy";

const SECONDS_IN_DAYS = 24 * 3600;
const LONGEST_POSSIBLE_MIGRATION = 365 * SECONDS_IN_DAYS;
const CRD_API_VERSION = "migrations.opensearch.org/v1alpha1";

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

export const ResourceManagement = WorkflowBuilder.create({
    k8sResourceName: "resource-management",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)

    .addTemplate("patchDataSnapshotCompleted", t => buildPatchStatusTemplate(t, "DataSnapshot", {
        snapshotName: "",
        configChecksum: "",
        checksumForSnapshotMigration: ""
    }))
    .addTemplate("patchCapturedTrafficRunning", t => buildPatchStatusTemplate(t, "CapturedTraffic", {}))
    .addTemplate("patchCapturedTrafficReady", t => buildPatchStatusTemplate(t, "CapturedTraffic", {
        configChecksum: "",
        checksumForSnapshot: "",
        checksumForReplayer: ""
    }))
    .addTemplate("patchCapturedTrafficError", t => buildPatchStatusTemplate(t, "CapturedTraffic", {}))
    .addTemplate("patchSnapshotMigrationCompleted", t => buildPatchStatusTemplate(t, "SnapshotMigration", {
        configChecksum: "",
        checksumForReplayer: ""
    }))
    .addTemplate("patchTrafficReplayReady", t => buildPatchStatusTemplate(t, "TrafficReplay", {
        configChecksum: ""
    }))


    // ── Wait templates (resource get with retry) ─────────────────────────

    .addTemplate("waitForKafkaClusterCreated", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addWaitForNewResource(b => b
            .setDefinition({
                resourceKindAndName: expr.concat(expr.literal("kafka/"), b.inputs.resourceName),
                waitForCreation: {
                    kubectlImage: b.inputs.imageMigrationConsoleLocation,
                    kubectlImagePullPolicy: b.inputs.imageMigrationConsolePullPolicy,
                    maxDurationSeconds: LONGEST_POSSIBLE_MIGRATION
                }
            })
        )
    )


    .addTemplate("waitForKafkaClusterReady", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addWaitForExistingResource(b => b
            .setDefinition({
                resource: {
                    apiVersion: "kafka.strimzi.io/v1",
                    kind: "Kafka",
                    name: b.inputs.resourceName
                },
                conditions: {
                    successCondition: expr.concat(
                        expr.literal("status.listeners, metadata.annotations.migration-configChecksum == "),
                        b.inputs.configChecksum
                    ),
                    failureCondition: "status.conditions.0.type == NotReady"
                }
            })
            .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
        )
    )


    .addTemplate("waitForKafkaCluster", t => t
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(b => b
            .addStep("waitForCreation", INTERNAL, "waitForKafkaClusterCreated", c =>
                c.register({...selectInputsForRegister(b, c), resourceName: b.inputs.resourceName})
            )
            .addStep("waitForReady", INTERNAL, "waitForKafkaClusterReady", c =>
                c.register({...selectInputsForRegister(b, c), resourceName: b.inputs.resourceName, configChecksum: b.inputs.configChecksum})
            )
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

    .addTemplate("waitForCapturedTraffic", t => t
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
            .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
        )
    )


    .addTemplate("waitForDataSnapshot", t => t
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
            .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
        )
    )


    .addTemplate("waitForSnapshotMigration", t => t
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
            .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
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

    .addTemplate("waitForApproval", t => t
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
        )
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


    // ── Config checksum annotation patch (for resources we don't own) ────

    .addTemplate("patchConfigChecksumAnnotation", t => t
        .addRequiredInput("resourceApiVersion", typeToken<string>())
        .addRequiredInput("resourceKind", typeToken<string>())
        .addRequiredInput("resourceName", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
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
                            "migration-configChecksum": makeStringTypeProxy(b.inputs.configChecksum)
                        }
                    }
                }
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    // ── Workflow UID approval annotation patch ───────────────────────────

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
                            "migrations.opensearch.org/approved-by-run":
                                makeStringTypeProxy(expr.getWorkflowValue("uid"))
                        }
                    }
                }
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    )


    .getFullScope();
