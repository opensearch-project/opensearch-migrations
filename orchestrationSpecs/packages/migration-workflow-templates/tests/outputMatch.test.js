"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
var fs = require("fs");
var path = require("path");
var jest_diff_1 = require("jest-diff");
var argo_workflow_builders_1 = require("@opensearch-migrations/argo-workflow-builders");
var allWorkflowTemplates_1 = require("../src/workflowTemplates/allWorkflowTemplates");
var snapshotDir = path.join(__dirname, '__snapshots__');
expect.extend({
    toMatchFileSnapshot: function (received, filename) {
        var filePath = path.join(snapshotDir, filename);
        var content = JSON.stringify(received, null, 2) + '\n';
        var snapshotState = expect.getState().snapshotState;
        var updating = snapshotState._updateSnapshot === 'all';
        if (updating || !fs.existsSync(filePath)) {
            fs.mkdirSync(snapshotDir, { recursive: true });
            fs.writeFileSync(filePath, content);
            snapshotState.updated++;
            return { pass: true, message: function () { return ''; } };
        }
        var existing = fs.readFileSync(filePath, 'utf-8');
        if (content === existing) {
            snapshotState.matched++;
            return { pass: true, message: function () { return ''; } };
        }
        snapshotState.unmatched++;
        return {
            pass: false,
            message: function () { var _a; return "Snapshot mismatch: ".concat(filename, "\n\n").concat((_a = (0, jest_diff_1.diff)(existing, content)) !== null && _a !== void 0 ? _a : '', "\n\nRun with --updateSnapshot to update."); }
        };
    }
});
describe('workflow template renderings', function () {
    var cases = allWorkflowTemplates_1.AllWorkflowTemplates
        .map(function (t) { return ({ name: t.metadata.k8sMetadata.name, template: t }); })
        .sort(function (a, b) { return a.name.localeCompare(b.name); });
    test.each(cases)('$name', function (_a) {
        var name = _a.name, template = _a.template;
        var result = (0, argo_workflow_builders_1.renderWorkflowTemplate)(template);
        var camel = name.replace(/-([a-z])/g, function (_, c) { return c.toUpperCase(); });
        expect(result).toMatchFileSnapshot("".concat(camel, ".snap.json"));
    });
});
