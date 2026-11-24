import {z} from "zod";
import {
    CLUSTER_VERSION_STRING,
    COMPLETE_SNAPSHOT_CONFIG,
    DEFAULT_RESOURCES,
    NAMED_TARGET_CLUSTER_CONFIG,
    ARGO_RFS_OPTIONS,
} from "@opensearch-migrations/schemas";
import {
    BaseExpression,
    expr,
    INTERNAL,
    selectInputsForRegister,
    Serialized,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {MigrationConsole} from "./migrationConsole";
import {ResourceManagement} from "./resourceManagement";
import {DocumentBulkLoad, getRfsDeploymentName} from "./documentBulkLoad";
import {RfsCoordinatorCluster, getRfsCoordinatorClusterName, makeRfsCoordinatorConfig} from "./rfsCoordinatorCluster";
import {CommonWorkflowParameters, workflowScriptCommand, workflowScriptRootEnvVars} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {getHttpAuthSecretName} from "./commonUtils/clusterSettingManipulators";
import {CONTAINER_TEMPLATE_RETRY_STRATEGY} from "./commonUtils/resourceRetryStrategy";
import {minAvailableForSingleReplicaDependency, scalingFromOptions} from "./commonUtils/scalableWorkload";
import {RFS_MEMORY_TIER_LABEL} from "./commonUtils/resourceLabels";
import {CONTAINER_NAMES} from "../containerNames";

// ── Memory-tier scaling plumbing ────────────────────────────────────────────
// The workflow is the POLICY AUTHOR only: at setup it renders the complete per-tier Deployment
// manifests and the tier table into a ConfigMap, creates the tier-0 runtime, and applies the
// monitor CronJob. All fleet reconciliation (per-sweep sizing, tier activation/reactivation,
// budget arbitration, terminal status) lives in the monitor. No per-tier workflow steps, no
// loops, no recursion: after setup, the workflow only waits on the SnapshotMigration CR.

export function getRfsTierPolicyConfigMapName(sessionName: BaseExpression<string>) {
    return expr.concat(sessionName, expr.literal("-rfs-tier-policy"));
}

function memoryScalingEnabled(
    documentBackfillConfig: BaseExpression<Serialized<z.infer<typeof ARGO_RFS_OPTIONS>>>
): BaseExpression<boolean, "complicatedExpression"> {
    return expr.hasKey(expr.deserializeRecord(documentBackfillConfig), "memoryScalingTierTable");
}

export const RfsBackfill = WorkflowBuilder.create({
    k8sResourceName: "rfs-backfill",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)

    // Renders the per-tier Deployment + PDB manifests and the fleet policy into the tier-policy
    // ConfigMap by deriving each tier from the LIVE tier-0 objects (so the stored shapes can
    // never drift from what actually runs), then re-applies nothing: the monitor creates and
    // scales tier fleets from this ConfigMap on its sweeps.
    .addTemplate("renderTierPolicy", t => t
        .addRequiredInput("sessionName", typeToken<string>())
        .addRequiredInput("crdName", typeToken<string>())
        .addRequiredInput("crdUid", typeToken<string>())
        .addRequiredInput("tierTableJson", typeToken<string>())
        .addRequiredInput("maxTotalPods", typeToken<string>())
        .addRequiredInput("escalationApproval", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addContainer(c => c
            .addImageInfo(c.inputs.imageMigrationConsoleLocation, c.inputs.imageMigrationConsolePullPolicy)
            .addResources(DEFAULT_RESOURCES.SHELL_MIGRATION_CONSOLE_CLI)
            .addCommand(["/bin/bash", "-lc"])
            .addEnvVarsFromRecord({
                SESSION_NAME: c.inputs.sessionName,
                SNAPSHOT_MIGRATION_NAME: c.inputs.crdName,
                SM_UID: c.inputs.crdUid,
                TIER_TABLE_JSON: c.inputs.tierTableJson,
                MAX_TOTAL_PODS: c.inputs.maxTotalPods,
                ESCALATION_APPROVAL: c.inputs.escalationApproval,
                TIER_POLICY_CONFIGMAP: getRfsTierPolicyConfigMapName(c.inputs.sessionName),
                TIER0_WORKLOAD_NAME: expr.concat(
                    getRfsDeploymentName(c.inputs.sessionName), expr.literal("-t0")),
                RFS_MEMORY_TIER_LABEL: expr.literal(RFS_MEMORY_TIER_LABEL),
                BULK_LOADER_CONTAINER: expr.literal(CONTAINER_NAMES.BULK_LOADER),
                ...workflowScriptRootEnvVars(t.inputs.workflowParameters.workflowScriptsRoot)
            })
            .addArgs([workflowScriptCommand("renderRfsTierPolicy.sh")])
        )
        .addRetryParameters(CONTAINER_TEMPLATE_RETRY_STRATEGY)
    )

    // Owns RFS lifecycle setup for one SnapshotMigration. Moved from DocumentBulkLoad's
    // setupAndRunBulkLoad so fullMigration's node tree stays small and all RFS matters live here.
    .addTemplate("setupAndRunRfsBackfill", t => t
        .addRequiredInput("sourceVersion", typeToken<z.infer<typeof CLUSTER_VERSION_STRING>>())
        .addRequiredInput("sourceLabel", typeToken<string>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("sessionName", typeToken<string>())
        .addRequiredInput("documentBackfillConfig", typeToken<z.infer<typeof ARGO_RFS_OPTIONS>>())
        .addRequiredInput("migrationLabel", typeToken<string>())
        .addRequiredInput("crdName", typeToken<string>())
        .addRequiredInput("crdUid", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("checksumForReplayer", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot", "MigrationConsole", "CoordinatorCluster"]))

        .addSteps(b => {
            const backfillConfig = expr.deserializeRecord(b.inputs.documentBackfillConfig);
            const createRfsCluster = expr.not(
                expr.get(backfillConfig, "useTargetClusterForWorkCoordination"));
            const rfsCoordinatorConfig = expr.ternary(
                createRfsCluster,
                expr.serialize(makeRfsCoordinatorConfig(getRfsCoordinatorClusterName(b.inputs.sessionName))),
                b.inputs.targetConfig
            );
            const scaling = scalingFromOptions(b.inputs.documentBackfillConfig);
            const scalingEnabled = memoryScalingEnabled(b.inputs.documentBackfillConfig);
            const tierTable = expr.getLoose(backfillConfig, "memoryScalingTierTable");
            // The fleet-wide pod budget: maxTotalPods when set, else podReplicas keeps its
            // meaning as the total (a maximum, not a constant, once scaling is on).
            const effectiveMaxTotalPods = expr.dig(backfillConfig, ["maxTotalPods"],
                expr.cast(expr.get(backfillConfig, "podReplicas")).to<number>());
            const escalationApproval = expr.dig(
                backfillConfig, ["memoryScalingEscalationApproval"], "auto");
            const backfillSession = expr.ternary(
                scalingEnabled,
                expr.serialize(expr.makeDict({
                    sessionName: b.inputs.sessionName,
                    deploymentName: getRfsDeploymentName(b.inputs.sessionName),
                    memoryScaling: expr.makeDict({
                        tiers: expr.cast(tierTable).to<string[]>(),
                        max_total_pods: expr.cast(effectiveMaxTotalPods).to<number>(),
                        escalation_approval: expr.asString(escalationApproval),
                    }),
                })),
                expr.serialize(expr.makeDict({
                    sessionName: b.inputs.sessionName,
                    deploymentName: getRfsDeploymentName(b.inputs.sessionName)
                })));
            return b
                .addStep("setupRfsConsoleConfig", MigrationConsole, "getConsoleConfig", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        targetConfig: rfsCoordinatorConfig,
                        backfillSession
                    })
                )

                .addStep("applyRfsDoneCronJob", DocumentBulkLoad, "applyRfsDoneCronJob", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        sessionName: b.inputs.sessionName,
                        crdName: b.inputs.crdName,
                        crdUid: b.inputs.crdUid,
                        configChecksum: b.inputs.configChecksum,
                        checksumForReplayer: b.inputs.checksumForReplayer,
                        usesDedicatedCoordinator: expr.ternary(createRfsCluster, expr.literal("true"), expr.literal("false")),
                        fromSnapshotMigrationK8sLabel: b.inputs.migrationLabel,
                        consoleConfigContents: c.steps.setupRfsConsoleConfig.outputs.configContents,
                        memoryScalingEnabled: expr.ternary(scalingEnabled, expr.literal("true"), expr.literal("")),
                        tierPolicyConfigMap: expr.ternary(scalingEnabled,
                            expr.asString(getRfsTierPolicyConfigMapName(b.inputs.sessionName)), expr.literal("")),
                    })
                )

                .addStep("createRfsCoordinator", RfsCoordinatorCluster, "createRfsCoordinator", c =>
                        c.register({
                            clusterName: getRfsCoordinatorClusterName(b.inputs.sessionName),
                            coordinatorImage: b.inputs.imageCoordinatorClusterLocation,
                            ownerName: b.inputs.crdName,
                            ownerUid: b.inputs.crdUid,
                            minPodReplicas: minAvailableForSingleReplicaDependency(scaling.minPodReplicas),
                            sourceK8sLabel: b.inputs.sourceLabel,
                            targetK8sLabel: expr.jsonPathStrict(b.inputs.targetConfig, "label"),
                            snapshotK8sLabel: expr.jsonPathStrict(b.inputs.snapshotConfig, "label"),
                            fromSnapshotMigrationK8sLabel: b.inputs.migrationLabel
                        }),
                    {when: {templateExp: createRfsCluster}}
                )

                // Feature off: today's single-deployment runtime, byte for byte.
                .addStep("startHistoricalBackfillFromConfig", DocumentBulkLoad, "startHistoricalBackfillFromConfig", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        rfsCoordinatorConfig
                    }),
                    {when: {templateExp: expr.not(scalingEnabled)}}
                )

                // Feature on: tier-0 runtime (sidecar witness, tier label, tier env), replicas
                // bootstrap at 1; the monitor's first sweep after shard_setup right-sizes it.
                .addStep("startTierZeroBackfillFromConfig", DocumentBulkLoad, "startTierZeroBackfillFromConfig", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        rfsCoordinatorConfig
                    }),
                    {when: {templateExp: scalingEnabled}}
                )

                .addStep("renderTierPolicy", INTERNAL, "renderTierPolicy", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        sessionName: b.inputs.sessionName,
                        crdName: b.inputs.crdName,
                        crdUid: b.inputs.crdUid,
                        tierTableJson: expr.asString(expr.serialize(expr.cast(tierTable).to<string[]>())),
                        maxTotalPods: expr.asString(effectiveMaxTotalPods),
                        escalationApproval: expr.asString(escalationApproval),
                    }),
                    {when: {templateExp: scalingEnabled}}
                )

                .addStep("waitIndefinitelyForSnapshotMigration", ResourceManagement, "waitIndefinitelyForSnapshotMigration", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        resourceName: b.inputs.crdName,
                        configChecksum: b.inputs.configChecksum,
                        checksumField: expr.literal("configChecksum")
                    })
                );
        })
    )

    .getFullScope();
