import {z} from "zod";
import {CreateSnapshot} from "./createSnapshot";
import {ARGO_CREATE_SNAPSHOT_OPTIONS, SNAPSHOT_NAME_CONFIG} from "@opensearch-migrations/schemas";
import {
    COMPLETE_SNAPSHOT_CONFIG,
    USER_CREATE_SNAPSHOT_OPTIONS,
    DYNAMIC_SNAPSHOT_CONFIG, NAMED_SOURCE_CLUSTER_CONFIG_WITHOUT_SNAPSHOT_INFO
} from "@opensearch-migrations/schemas";
import {
    BaseExpression,
    expr,
    getAcceptedRegisterKeys,
    INTERNAL, makeDirectTypeProxy,
    selectInputsForKeys,
    selectInputsForRegister,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";

import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";

export const CreateOrGetSnapshot = WorkflowBuilder.create({
    k8sResourceName: "create-or-get-snapshot",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("getSnapshotName", t => t
        .addRequiredInput("sourceLabel", typeToken<string>())
        .addRequiredInput("snapshotNameConfig", typeToken<z.infer<typeof SNAPSHOT_NAME_CONFIG>>())
        .addRequiredInput("snapshotPrefix", typeToken<string>())
        .addRequiredInput("uniqueRunNonce", typeToken<string>())

        .addSteps(b => b
            .addStepGroup(c => c))
        .addExpressionOutput("snapshotName", b =>
            expr.ternary(
                expr.hasKey(expr.deserializeRecord(b.inputs.snapshotNameConfig), "createSnapshotConfig"),
                expr.concatWith("_",
                    b.inputs.sourceLabel,
                    b.inputs.snapshotPrefix,
                    b.inputs.uniqueRunNonce
                ),
                expr.getLoose(expr.deserializeRecord(b.inputs.snapshotNameConfig), "externallyManagedSnapshotName"))
        )
        .addExpressionOutput("autoCreate", b =>
            expr.hasKey(expr.deserializeRecord(b.inputs.snapshotNameConfig), "createSnapshotConfig")
        )
    )


    .addTemplate("createOrGetSnapshot", t => t
        .addRequiredInput("createSnapshotConfig", typeToken<z.infer<typeof ARGO_CREATE_SNAPSHOT_OPTIONS>>())
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG_WITHOUT_SNAPSHOT_INFO>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof DYNAMIC_SNAPSHOT_CONFIG>>())
        .addRequiredInput("snapshotPrefix", typeToken<string>())
        .addRequiredInput("uniqueRunNonce", typeToken<string>())
        .addRequiredInput("semaphoreConfigMapName", typeToken<string>())
        .addRequiredInput("semaphoreKey", typeToken<string>())
        .addRequiredInput("configChecksum", typeToken<string>())
        .addRequiredInput("dataSnapshotName", typeToken<string>())
        .addRequiredInput("dataSnapshotUid", typeToken<string>())
        // Solr import-prepare: when non-empty, this snapshot is an externally-managed Solr snapshot
        // that needs the schema uploaded via CreateSnapshot --mode import. The value is the
        // pre-existing snapshot name; it is used verbatim (not generated, not lowercased) so it
        // matches the snapshot already present in the repo. Empty for the normal create path.
        .addOptionalInput("importExternalSnapshotName", c => "")
        // CreateSnapshot --source-type forwarded to snapshotWorkflow. Set to "solr" on the import
        // path so Java does not rely on live engine detection; empty on the create path so Java
        // auto-detects the engine. Branching in snapshotWorkflow is controlled by mode.
        .addOptionalInput("sourceType", c => "")
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addSteps(b => {
            // The Solr import-prepare path and the normal create path share one snapshotWorkflow
            // call. isImport selects the external snapshot name and sourceType:"solr" for the
            // import-prepare path. snapshotWorkflow then branches on createSnapshotConfig.mode, which
            // configProcessor sets to "import" for Solr importConfig snapshots. The step is still
            // gated on autoCreate-or-import so a plain externally-managed snapshot (which never reaches
            // this template) stays a no-op.
            const isImport = expr.not(expr.isEmpty(b.inputs.importExternalSnapshotName));
            // Compute autoCreate as a real boolean from the config input (same check getSnapshotName
            // makes), NOT from getSnapshotName's string output parameter. The when-condition ORs it
            // with isImport, and govaluate's `||` requires boolean operands — applying `||` to the
            // string "true"/"false" output parameter throws at runtime and silently skips the step.
            const autoCreate = expr.hasKey(
                expr.get(expr.deserializeRecord(b.inputs.snapshotConfig), "config"),
                "createSnapshotConfig");
            return b
            .addStep("getSnapshotName", INTERNAL, "getSnapshotName", c => c.register({
                    ...selectInputsForRegister(b, c),
                    sourceLabel: expr.get(expr.deserializeRecord(b.inputs.sourceConfig), "label"),
                    snapshotNameConfig: expr.serialize(
                        expr.get(expr.deserializeRecord(b.inputs.snapshotConfig), "config")),
                })
            )
            .addStep("createSnapshot", CreateSnapshot, "snapshotWorkflow",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    ...selectInputsForKeys(b, getAcceptedRegisterKeys(c)),
                    snapshotConfig: expr.serialize(
                        expr.makeDict({
                            repoConfig: expr.jsonPathStrict(b.inputs.snapshotConfig, "repoConfig"),
                            // Import: use the external name verbatim (must match the existing snapshot).
                            // Create: use the generated, lowercased name.
                            snapshotName: expr.ternary(
                                isImport,
                                b.inputs.importExternalSnapshotName,
                                expr.toLowerCase(c.steps.getSnapshotName.outputs.snapshotName)),
                            label: expr.jsonPathStrict(b.inputs.snapshotConfig, "label"),
                        })
                    ),
                    sourceType: expr.ternary(isImport, expr.literal("solr"), expr.literal("")),
                    semaphoreConfigMapName: b.inputs.semaphoreConfigMapName,
                    semaphoreKey: b.inputs.semaphoreKey,
                    dataSnapshotName: b.inputs.dataSnapshotName,
                    dataSnapshotUid: b.inputs.dataSnapshotUid
                }), {
                    when: () => ({templateExp: expr.or(autoCreate, isImport)})
                }
            )
        })
        .addExpressionOutput("snapshotConfig", c =>
            expr.serialize(expr.makeDict({
                // Import uses the external name verbatim; create uses the generated name.
                snapshotName: expr.ternary(
                    expr.isEmpty(c.inputs.importExternalSnapshotName),
                    c.steps.getSnapshotName.outputs.snapshotName,
                    c.inputs.importExternalSnapshotName),
                repoConfig: expr.get(expr.deserializeRecord(c.inputs.snapshotConfig), "repoConfig"),
                label: expr.get(expr.deserializeRecord(c.inputs.snapshotConfig), "label")
            })))
    )

    .getFullScope();
