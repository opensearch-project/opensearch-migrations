"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.ResourceManagement = void 0;
var argo_workflow_builders_1 = require("@opensearch-migrations/argo-workflow-builders");
var workflowParameters_1 = require("./commonUtils/workflowParameters");
var imageDefinitions_1 = require("./commonUtils/imageDefinitions");
var SECONDS_IN_DAYS = 24 * 3600;
var LONGEST_POSSIBLE_MIGRATION = 365 * SECONDS_IN_DAYS;
var CRD_API_VERSION = "migrations.opensearch.org/v1alpha1";
exports.ResourceManagement = argo_workflow_builders_1.WorkflowBuilder.create({
    k8sResourceName: "resource-management",
    serviceAccountName: "argo-workflow-executor"
})
    .addParams(workflowParameters_1.CommonWorkflowParameters)
    // ── Wait templates (resource get with retry) ─────────────────────────
    .addTemplate("waitForKafkaTopic", function (b) { return b
    .addRequiredInput("resourceName", (0, argo_workflow_builders_1.typeToken)())
    .addInputsFromRecord((0, imageDefinitions_1.makeRequiredImageParametersForKeys)(["MigrationConsole"]))
    .addWaitForNewResource(function (b) { return b
    .setDefinition({
    resourceKindAndName: argo_workflow_builders_1.expr.concat(argo_workflow_builders_1.expr.literal(""), b.inputs.resourceName),
    waitForCreation: {
        kubectlImage: b.inputs.imageMigrationConsoleLocation,
        kubectlImagePullPolicy: b.inputs.imageMigrationConsolePullPolicy,
        maxDurationSeconds: LONGEST_POSSIBLE_MIGRATION
    }
}); }); })
    .addTemplate("waitForCapturedTraffic", function (t) { return t
    .addRequiredInput("resourceName", (0, argo_workflow_builders_1.typeToken)())
    .addInputsFromRecord((0, imageDefinitions_1.makeRequiredImageParametersForKeys)(["MigrationConsole"]))
    .addWaitForExistingResource(function (b) { return b
    .setDefinition({
    resource: {
        apiVersion: CRD_API_VERSION,
        kind: "CapturedTraffic",
        name: b.inputs.resourceName
    },
    conditions: { successCondition: "status.phase == Ready" }
}); }); })
    .addTemplate("waitForDataSnapshot", function (t) { return t
    .addRequiredInput("resourceName", (0, argo_workflow_builders_1.typeToken)())
    .addInputsFromRecord((0, imageDefinitions_1.makeRequiredImageParametersForKeys)(["MigrationConsole"]))
    .addWaitForExistingResource(function (b) { return b
    .setDefinition({
    resource: {
        apiVersion: CRD_API_VERSION,
        kind: "DataSnapshot",
        name: b.inputs.resourceName
    },
    conditions: { successCondition: "status.phase == Ready" }
}); }); })
    .addTemplate("waitForSnapshotMigration", function (t) { return t
    .addRequiredInput("resourceName", (0, argo_workflow_builders_1.typeToken)())
    .addInputsFromRecord((0, imageDefinitions_1.makeRequiredImageParametersForKeys)(["MigrationConsole"]))
    .addWaitForExistingResource(function (b) { return b
    .setDefinition({
    resource: {
        apiVersion: CRD_API_VERSION,
        kind: "SnapshotMigration",
        name: b.inputs.resourceName
    },
    conditions: { successCondition: "status.phase == Ready" }
}); }); })
    // ── CRD patch-to-ready templates ─────────────────────────────────────
    .addTemplate("patchCapturedTrafficReady", function (t) { return t
    .addRequiredInput("resourceName", (0, argo_workflow_builders_1.typeToken)())
    .addResourceTask(function (b) { return b
    .setDefinition({
    action: "patch",
    flags: ["--type", "merge", "--subresource=status"],
    manifest: {
        apiVersion: CRD_API_VERSION,
        kind: "CapturedTraffic",
        metadata: { name: b.inputs.resourceName },
        status: { phase: "Ready" }
    }
}); }); })
    .addTemplate("patchDataSnapshotReady", function (t) { return t
    .addRequiredInput("resourceName", (0, argo_workflow_builders_1.typeToken)())
    .addRequiredInput("snapshotName", (0, argo_workflow_builders_1.typeToken)())
    .addResourceTask(function (b) { return b
    .setDefinition({
    action: "patch",
    flags: ["--type", "merge", "--subresource=status"],
    manifest: {
        apiVersion: CRD_API_VERSION,
        kind: "DataSnapshot",
        metadata: { name: b.inputs.resourceName },
        status: { phase: "Ready", snapshotName: b.inputs.snapshotName }
    }
}); }); })
    .addTemplate("patchSnapshotMigrationReady", function (t) { return t
    .addRequiredInput("resourceName", (0, argo_workflow_builders_1.typeToken)())
    .addResourceTask(function (b) { return b
    .setDefinition({
    action: "patch",
    flags: ["--type", "merge", "--subresource=status"],
    manifest: {
        apiVersion: CRD_API_VERSION,
        kind: "SnapshotMigration",
        metadata: { name: b.inputs.resourceName },
        status: { phase: "Ready" }
    }
}); }); })
    .addTemplate("readDataSnapshotName", function (t) { return t
    .addRequiredInput("resourceName", (0, argo_workflow_builders_1.typeToken)())
    .addResourceTask(function (b) { return b
    .setDefinition({
    action: "get",
    manifest: {
        apiVersion: CRD_API_VERSION,
        kind: "DataSnapshot",
        metadata: { name: b.inputs.resourceName }
    }
})
    .addJsonPathOutput("snapshotName", "{.status.snapshotName}", (0, argo_workflow_builders_1.typeToken)()); }); })
    .getFullScope();
