"use strict";
// TODO
Object.defineProperty(exports, "__esModule", { value: true });
exports.CaptureProxy = void 0;
var argo_workflow_builders_1 = require("@opensearch-migrations/argo-workflow-builders");
var workflowParameters_1 = require("./commonUtils/workflowParameters");
var imageDefinitions_1 = require("./commonUtils/imageDefinitions");
exports.CaptureProxy = argo_workflow_builders_1.WorkflowBuilder.create({
    k8sResourceName: "capture-proxy",
    serviceAccountName: "argo-workflow-executor"
})
    .addParams(workflowParameters_1.CommonWorkflowParameters)
    .addTemplate("deployProxyService", function (t) { return t
    .addRequiredInput("serviceName", (0, argo_workflow_builders_1.typeToken)())
    .addRequiredInput("port", (0, argo_workflow_builders_1.typeToken)())
    .addSteps(function (b) { return b.addStepGroup(function (c) { return c; }); }); })
    .addTemplate("deployCaptureProxy", function (t) { return t
    .addRequiredInput("sourceConfig", (0, argo_workflow_builders_1.typeToken)())
    .addRequiredInput("proxyConfig", (0, argo_workflow_builders_1.typeToken)())
    .addInputsFromRecord((0, imageDefinitions_1.makeRequiredImageParametersForKeys)(["CaptureProxy"]))
    .addSteps(function (b) { return b.addStepGroup(function (c) { return c; }); }); } // TODO convert to a resource!
)
    .getFullScope();
