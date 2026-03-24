"use strict";
var __assign = (this && this.__assign) || function () {
    __assign = Object.assign || function(t) {
        for (var s, i = 1, n = arguments.length; i < n; i++) {
            s = arguments[i];
            for (var p in s) if (Object.prototype.hasOwnProperty.call(s, p))
                t[p] = s[p];
        }
        return t;
    };
    return __assign.apply(this, arguments);
};
var __spreadArray = (this && this.__spreadArray) || function (to, from, pack) {
    if (pack || arguments.length === 2) for (var i = 0, l = from.length, ar; i < l; i++) {
        if (ar || !(i in from)) {
            if (!ar) ar = Array.prototype.slice.call(from, 0, i);
            ar[i] = from[i];
        }
    }
    return to.concat(ar || Array.prototype.slice.call(from));
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.MetadataMigration = void 0;
exports.makeRepoParamDict = makeRepoParamDict;
var schemas_1 = require("@opensearch-migrations/schemas");
var argo_workflow_builders_1 = require("@opensearch-migrations/argo-workflow-builders");
var workflowParameters_1 = require("./commonUtils/workflowParameters");
var imageDefinitions_1 = require("./commonUtils/imageDefinitions");
var clusterSettingManipulators_1 = require("./commonUtils/clusterSettingManipulators");
var clusterSettingManipulators_2 = require("./commonUtils/clusterSettingManipulators");
var basicCredsGetters_1 = require("./commonUtils/basicCredsGetters");
var configContextPathConstructors_1 = require("./commonUtils/configContextPathConstructors");
var COMMON_METADATA_PARAMETERS = __assign({ snapshotConfig: (0, argo_workflow_builders_1.defineRequiredParam)({ description: "Snapshot storage details (region, endpoint, etc)" }), sourceLabel: (0, argo_workflow_builders_1.defineRequiredParam)(), sourceVersion: (0, argo_workflow_builders_1.defineRequiredParam)(), targetConfig: (0, argo_workflow_builders_1.defineRequiredParam)(), migrationLabel: (0, argo_workflow_builders_1.defineRequiredParam)() }, (0, imageDefinitions_1.makeRequiredImageParametersForKeys)(["MigrationConsole"]));
function makeRepoParamDict(repoConfig, includes3LocalDir) {
    return argo_workflow_builders_1.expr.mergeDicts(argo_workflow_builders_1.expr.mergeDicts(argo_workflow_builders_1.expr.ternary(argo_workflow_builders_1.expr.isEmpty(argo_workflow_builders_1.expr.dig(repoConfig, ["endpoint"], (""))), argo_workflow_builders_1.expr.makeDict({}), argo_workflow_builders_1.expr.makeDict({ "s3Endpoint": argo_workflow_builders_1.expr.getLoose(repoConfig, "endpoint") })), argo_workflow_builders_1.expr.ternary(argo_workflow_builders_1.expr.isEmpty(argo_workflow_builders_1.expr.dig(repoConfig, ["s3RoleArn"], (""))), argo_workflow_builders_1.expr.makeDict({}), argo_workflow_builders_1.expr.makeDict({ "s3RoleArn": argo_workflow_builders_1.expr.getLoose(repoConfig, "s3RoleArn") }))), argo_workflow_builders_1.expr.makeDict(__assign({ "s3RepoUri": argo_workflow_builders_1.expr.get(repoConfig, "s3RepoPathUri"), "s3Region": argo_workflow_builders_1.expr.get(repoConfig, "awsRegion") }, (includes3LocalDir ? { "s3LocalDir": argo_workflow_builders_1.expr.literal("/tmp") } : {}))));
}
function makeParamsDict(sourceVersion, targetConfig, snapshotConfig, options) {
    return argo_workflow_builders_1.expr.mergeDicts(argo_workflow_builders_1.expr.mergeDicts((0, clusterSettingManipulators_1.makeTargetParamDict)(targetConfig), 
    // TODO - tighten the type on mergeDicts - it allowed this to go through w/out first calling fromJSON
    argo_workflow_builders_1.expr.omit(argo_workflow_builders_1.expr.deserializeRecord(options), "loggingConfigurationOverrideConfigMap", "jvmArgs")), argo_workflow_builders_1.expr.mergeDicts(argo_workflow_builders_1.expr.makeDict({
        "snapshotName": argo_workflow_builders_1.expr.get(argo_workflow_builders_1.expr.deserializeRecord(snapshotConfig), "snapshotName"),
        "sourceVersion": sourceVersion
    }), makeRepoParamDict(argo_workflow_builders_1.expr.omit(argo_workflow_builders_1.expr.get(argo_workflow_builders_1.expr.deserializeRecord(snapshotConfig), "repoConfig"), "s3RoleArn"), true)));
}
function makeApprovalCheck(inputs, skipApprovalMap) {
    var innerSkipFlags = [];
    for (var _i = 2; _i < arguments.length; _i++) {
        innerSkipFlags[_i - 2] = arguments[_i];
    }
    return new argo_workflow_builders_1.FunctionExpression("sprig.dig", __spreadArray(__spreadArray(__spreadArray([], (0, configContextPathConstructors_1.getSourceTargetPathAndSnapshotAndMigrationIndex)(inputs.sourceLabel, inputs.targetConfig, argo_workflow_builders_1.expr.jsonPathStrict(inputs.snapshotConfig, "label"), inputs.migrationLabel), true), (innerSkipFlags !== undefined ? innerSkipFlags.map(function (f) { return argo_workflow_builders_1.expr.literal(f); }) : []), true), [
        argo_workflow_builders_1.expr.literal(false),
        argo_workflow_builders_1.expr.deserializeRecord(skipApprovalMap)
    ], false));
}
exports.MetadataMigration = argo_workflow_builders_1.WorkflowBuilder.create({
    k8sResourceName: "metadata-migration",
    serviceAccountName: "argo-workflow-executor"
})
    .addParams(workflowParameters_1.CommonWorkflowParameters)
    .addTemplate("runMetadata", function (t) { return t
    .addRequiredInput("commandMode", (0, argo_workflow_builders_1.typeToken)())
    .addRequiredInput("sourceVersion", (0, argo_workflow_builders_1.typeToken)())
    .addRequiredInput("targetConfig", (0, argo_workflow_builders_1.typeToken)())
    .addRequiredInput("snapshotConfig", (0, argo_workflow_builders_1.typeToken)())
    .addRequiredInput("metadataMigrationConfig", (0, argo_workflow_builders_1.typeToken)())
    .addInputsFromRecord((0, imageDefinitions_1.makeRequiredImageParametersForKeys)(["MigrationConsole"]))
    .addRequiredInput("sourceK8sLabel", (0, argo_workflow_builders_1.typeToken)())
    .addRequiredInput("targetK8sLabel", (0, argo_workflow_builders_1.typeToken)())
    .addRequiredInput("snapshotK8sLabel", (0, argo_workflow_builders_1.typeToken)())
    .addRequiredInput("fromSnapshotMigrationK8sLabel", (0, argo_workflow_builders_1.typeToken)())
    .addOptionalInput("taskK8sLabel", function (c) { return argo_workflow_builders_1.expr.ternary(argo_workflow_builders_1.expr.equals(c.inputParameters.commandMode, argo_workflow_builders_1.expr.literal("evaluate")), argo_workflow_builders_1.expr.literal("metadataEvaluate"), argo_workflow_builders_1.expr.literal("metadataMigrate")); })
    .addContainer(function (b) { return b
    .addImageInfo(b.inputs.imageMigrationConsoleLocation, b.inputs.imageMigrationConsolePullPolicy)
    .addVolumesFromRecord({
    'test-creds': {
        configMap: {
            name: argo_workflow_builders_1.expr.literal("localstack-test-creds"),
            optional: true
        },
        mountPath: "/config/credentials",
        readOnly: true
    }
})
    .addEnvVar("AWS_SHARED_CREDENTIALS_FILE", argo_workflow_builders_1.expr.ternary(argo_workflow_builders_1.expr.dig(argo_workflow_builders_1.expr.deserializeRecord(b.inputs.snapshotConfig), ["repoConfig", "useLocalStack"], false), argo_workflow_builders_1.expr.literal("/config/credentials/configuration"), argo_workflow_builders_1.expr.literal("")))
    .addEnvVar("JDK_JAVA_OPTIONS", argo_workflow_builders_1.expr.dig(argo_workflow_builders_1.expr.deserializeRecord(b.inputs.metadataMigrationConfig), ["jvmArgs"], ""))
    .addEnvVarsFromRecord((0, basicCredsGetters_1.getTargetHttpAuthCreds)((0, clusterSettingManipulators_2.getHttpAuthSecretName)(b.inputs.targetConfig)))
    .addResources(schemas_1.DEFAULT_RESOURCES.JAVA_MIGRATION_CONSOLE_CLI)
    .addCommand(["/root/metadataMigration/bin/MetadataMigration"])
    .addArgs([
    b.inputs.commandMode,
    argo_workflow_builders_1.expr.literal("---INLINE-JSON"),
    argo_workflow_builders_1.expr.asString(argo_workflow_builders_1.expr.serialize(makeParamsDict(b.inputs.sourceVersion, b.inputs.targetConfig, b.inputs.snapshotConfig, b.inputs.metadataMigrationConfig)))
])
    .addPodMetadata(function (_a) {
    var inputs = _a.inputs;
    return ({
        labels: {
            'migrations.opensearch.org/source': inputs.sourceK8sLabel,
            'migrations.opensearch.org/target': inputs.targetK8sLabel,
            'migrations.opensearch.org/snapshot': inputs.snapshotK8sLabel,
            'migrations.opensearch.org/from-snapshot-migration': inputs.fromSnapshotMigrationK8sLabel,
            'migrations.opensearch.org/task': inputs.taskK8sLabel
        }
    });
}); }); })
    .addTemplate("approveEvaluate", function (t) { return t
    .addRequiredInput("name", (0, argo_workflow_builders_1.typeToken)())
    .addSuspend(); })
    .addTemplate("approveMigrate", function (t) { return t
    .addRequiredInput("name", (0, argo_workflow_builders_1.typeToken)())
    .addSuspend(); })
    .addTemplate("migrateMetaData", function (t) { return t
    .addRequiredInput("metadataMigrationConfig", (0, argo_workflow_builders_1.typeToken)())
    .addInputsFromRecord(COMMON_METADATA_PARAMETERS)
    .addInputsFromRecord((0, configContextPathConstructors_1.getApprovalMap)(t.inputs.workflowParameters.approvalConfigMapName, (0, argo_workflow_builders_1.typeToken)()))
    .addOptionalInput("skipEvaluateApproval", function (c) {
    return makeApprovalCheck(c.inputParameters, c.inputParameters.skipApprovalMap, "evaluateMetadata");
})
    .addOptionalInput("skipMigrateApproval", function (c) {
    return makeApprovalCheck(c.inputParameters, c.inputParameters.skipApprovalMap, "migrateMetadata");
})
    .addOptionalInput("approvalNamePrefix", function (c) {
    return argo_workflow_builders_1.expr.concat(c.inputParameters.sourceLabel, argo_workflow_builders_1.expr.literal("."), argo_workflow_builders_1.expr.jsonPathStrict(c.inputParameters.targetConfig, "label"), argo_workflow_builders_1.expr.literal("."), argo_workflow_builders_1.expr.jsonPathStrict(c.inputParameters.snapshotConfig, "label"), argo_workflow_builders_1.expr.literal("."), c.inputParameters.migrationLabel, argo_workflow_builders_1.expr.literal("."));
})
    .addSteps(function (b) { return b
    .addStep("evaluateMetadata", argo_workflow_builders_1.INTERNAL, "runMetadata", function (c) {
    return c.register(__assign(__assign({}, (0, argo_workflow_builders_1.selectInputsForRegister)(b, c)), { commandMode: "evaluate", sourceK8sLabel: b.inputs.sourceLabel, targetK8sLabel: argo_workflow_builders_1.expr.jsonPathStrict(b.inputs.targetConfig, "label"), snapshotK8sLabel: argo_workflow_builders_1.expr.jsonPathStrict(b.inputs.snapshotConfig, "label"), fromSnapshotMigrationK8sLabel: b.inputs.migrationLabel }));
})
    .addStep("approveEvaluate", argo_workflow_builders_1.INTERNAL, "approveEvaluate", function (c) {
    return c.register({
        "name": argo_workflow_builders_1.expr.concat(b.inputs.approvalNamePrefix, argo_workflow_builders_1.expr.literal("evaluateMetadata"))
    });
}, { when: argo_workflow_builders_1.expr.not(argo_workflow_builders_1.expr.cast(b.inputs.skipEvaluateApproval).to()) })
    .addStep("migrateMetadata", argo_workflow_builders_1.INTERNAL, "runMetadata", function (c) {
    return c.register(__assign(__assign({}, (0, argo_workflow_builders_1.selectInputsForRegister)(b, c)), { commandMode: "migrate", sourceK8sLabel: b.inputs.sourceLabel, targetK8sLabel: argo_workflow_builders_1.expr.jsonPathStrict(b.inputs.targetConfig, "label"), snapshotK8sLabel: argo_workflow_builders_1.expr.jsonPathStrict(b.inputs.snapshotConfig, "label"), fromSnapshotMigrationK8sLabel: b.inputs.migrationLabel }));
})
    .addStep("approveMigrate", argo_workflow_builders_1.INTERNAL, "approveMigrate", function (c) {
    return c.register({
        "name": argo_workflow_builders_1.expr.concat(b.inputs.approvalNamePrefix, argo_workflow_builders_1.expr.literal("migrateMetadata"))
    });
}, { when: { templateExp: argo_workflow_builders_1.expr.not(argo_workflow_builders_1.expr.deserializeRecord(b.inputs.skipMigrateApproval)) } }); }); })
    .getFullScope();
