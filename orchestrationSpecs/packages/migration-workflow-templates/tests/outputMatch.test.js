"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
var argo_workflow_builders_1 = require("@opensearch-migrations/argo-workflow-builders");
var allWorkflowTemplates_1 = require("../src/workflowTemplates/allWorkflowTemplates");
describe('test workflow template renderings', function () {
    var cases = allWorkflowTemplates_1.AllWorkflowTemplates.map(function (input) { return ({
        name: input.metadata.k8sMetadata.name,
        input: input,
    }); })
        // keep order stable to avoid churn
        .sort(function (a, b) { return a.name.localeCompare(b.name); });
    test.each(cases)('$name', function (_a) {
        var input = _a.input;
        var result = (0, argo_workflow_builders_1.renderWorkflowTemplate)(input);
        // let the test name be the snapshot key
        expect(result).toMatchSnapshot();
    });
});
