"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
var src_1 = require("../../src");
var src_2 = require("../../src");
describe('WaitForExistingResource retry support', function () {
    it('renders retryStrategy when addRetryParameters is called', function () {
        var retryParams = {
            limit: "6",
            retryPolicy: "Always",
            backoff: { duration: "10", factor: "2", cap: "60" }
        };
        var wf = src_1.WorkflowBuilder.create({ k8sResourceName: 'test-wait-retry' })
            .addTemplate('waitWithRetry', function (t) { return t
            .addRequiredInput('resourceName', (0, src_1.typeToken)())
            .addWaitForExistingResource(function (b) { return b
            .setDefinition({
            resource: {
                apiVersion: "migrations.opensearch.org/v1alpha1",
                kind: "DataSnapshot",
                name: b.inputs.resourceName
            },
            conditions: { successCondition: "status.phase == Ready" }
        })
            .addRetryParameters(retryParams); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'waitwithretry'; });
        expect(template).toBeDefined();
        expect(template.retryStrategy).toEqual(retryParams);
        expect(template.resource).toBeDefined();
        expect(template.resource.successCondition).toBe("status.phase == Ready");
    });
    it('omits retryStrategy when addRetryParameters is not called', function () {
        var wf = src_1.WorkflowBuilder.create({ k8sResourceName: 'test-wait-no-retry' })
            .addTemplate('waitNoRetry', function (t) { return t
            .addRequiredInput('resourceName', (0, src_1.typeToken)())
            .addWaitForExistingResource(function (b) { return b
            .setDefinition({
            resource: {
                apiVersion: "v1",
                kind: "Pod",
                name: b.inputs.resourceName
            },
            conditions: { successCondition: "status.phase == Running" }
        }); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'waitnoretry'; });
        expect(template).toBeDefined();
        expect(template.retryStrategy).toBeUndefined();
    });
    it('renders successCondition and failureCondition expressions for existing-resource waits', function () {
        var wf = src_1.WorkflowBuilder.create({ k8sResourceName: 'test-wait-expression-conditions' })
            .addTemplate('waitWithExpressionConditions', function (t) { return t
            .addRequiredInput('resourceName', (0, src_1.typeToken)())
            .addRequiredInput('expectedState', (0, src_1.typeToken)())
            .addWaitForExistingResource(function (b) { return b
            .setDefinition({
            resource: {
                apiVersion: "v1",
                kind: "ConfigMap",
                name: b.inputs.resourceName
            },
            conditions: {
                successCondition: src_2.expr.concat(src_2.expr.literal("data.state == "), b.inputs.expectedState),
                failureCondition: src_2.expr.concat(src_2.expr.literal("data.state == failed-"), b.inputs.expectedState)
            }
        }); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'waitwithexpressionconditions'; });
        expect(template).toBeDefined();
        expect(template.resource.successCondition).toContain('{{=');
        expect(template.resource.successCondition).toContain('inputs.parameters.expectedState');
        expect(template.resource.failureCondition).toContain('{{=');
        expect(template.resource.failureCondition).toContain('inputs.parameters.expectedState');
    });
});
