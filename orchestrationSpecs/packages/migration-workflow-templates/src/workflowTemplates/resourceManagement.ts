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
    WorkflowBuilder
} from '@opensearch-migrations/argo-workflow-builders';
import type {WorkflowAndTemplatesScope} from '@opensearch-migrations/argo-workflow-builders';
import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {K8S_RESOURCE_RETRY_STRATEGY} from "./commonUtils/resourceRetryStrategy";

const SECONDS_IN_DAYS = 24 * 3600;
const LONGEST_POSSIBLE_MIGRATION = 365 * SECONDS_IN_DAYS;
const CRD_API_VERSION = "migrations.opensearch.org/v1alpha1";

type ReservedPatchInputNames = "resourceKind" | "resourceName" | "phase";
type StringStatusFields = Readonly<Record<string, AllowLiteralOrExpression<string>>>;
type NonReservedStringStatusFields = StringStatusFields & {
    [K in ReservedPatchInputNames]?: never;
};
type ProxiedStringStatusFields<T extends StringStatusFields> = { [K in keyof T]: string };
type InlinePatchRegisterValues<T extends StringStatusFields> = {
    resourceKind: AllowLiteralOrExpression<string>;
    resourceName: AllowLiteralOrExpression<string>;
    phase: AllowLiteralOrExpression<string>;
} & T;

function proxiedStringStatusFields<T extends StringStatusFields>(fields: T): ProxiedStringStatusFields<T> {
    const proxied = {} as ProxiedStringStatusFields<T>;
    for (const key of Object.keys(fields) as Array<keyof T>) {
        proxied[key] = makeStringTypeProxy(fields[key]);
    }
    return proxied;
}

function makeRequiredStringInputDefs<T extends StringStatusFields>(fields: T): InputParametersRecord {
    const defs: InputParametersRecord = {};
    for (const key of Object.keys(fields) as Array<keyof T>) {
        defs[String(key)] = {} as InputParamDef<string, true>;
    }
    return defs;
}

function placeholderStatusFields<T extends StringStatusFields>(
    fields: T
): ProxiedStringStatusFields<T> {
    const proxied = {} as ProxiedStringStatusFields<T>;
    for (const key of Object.keys(fields) as Array<keyof T>) {
        proxied[key] = `{{inputs.parameters.${String(key)}}}`;
    }
    return proxied;
}

export function inlinePatchResourceStatus<
    ExtraFields extends NonReservedStringStatusFields = {}
>(args: {
    resourceKind: AllowLiteralOrExpression<string>;
    resourceName: AllowLiteralOrExpression<string>;
    phase: AllowLiteralOrExpression<string>;
    extraStatusFields?: ExtraFields;
}) {
    const extraStatusFields = (args.extraStatusFields ?? {}) as ExtraFields;
    const inputDefs = {
        resourceKind: {} as InputParamDef<string, true>,
        resourceName: {} as InputParamDef<string, true>,
        phase: {} as InputParamDef<string, true>,
        ...makeRequiredStringInputDefs(extraStatusFields)
    } satisfies InputParametersRecord;

    return {
        registerValues: {
            resourceKind: args.resourceKind,
            resourceName: args.resourceName,
            phase: args.phase,
            ...extraStatusFields
        } as InlinePatchRegisterValues<ExtraFields>,
        inlineTemplate: <ParentWorkflowScope extends WorkflowAndTemplatesScope>(
            t: TemplateBuilder<ParentWorkflowScope, {}, {}, {}>
        ) => t
        .addInputsFromRecord(inputDefs)
        .addResourceTask(_b => _b
            .setDefinition({
                action: "patch",
                flags: ["--type", "merge", "--subresource=status"],
                manifest: {
                    apiVersion: CRD_API_VERSION,
                    kind: "{{inputs.parameters.resourceKind}}",
                    metadata: {name: "{{inputs.parameters.resourceName}}"},
                    status: {
                        phase: "{{inputs.parameters.phase}}",
                        ...placeholderStatusFields(extraStatusFields)
                    }
                }
            }))
        .addRetryParameters(K8S_RESOURCE_RETRY_STRATEGY)
    };
}

export const ResourceManagement = WorkflowBuilder.create({
    k8sResourceName: "resource-management",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


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
