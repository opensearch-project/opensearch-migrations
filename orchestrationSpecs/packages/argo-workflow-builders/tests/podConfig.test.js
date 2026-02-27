"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
var src_1 = require("../src");
var EXAMPLE_RESOURCES = {
    requests: { cpu: "100m", memory: "128Mi" },
    limits: { cpu: "200m", memory: "256Mi" }
};
describe('Pod Config - Metadata', function () {
    it('should render pod metadata with static labels and annotations', function () {
        var wf = src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-metadata',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addPodMetadata(function () { return ({
            labels: { app: 'test', tier: 'backend' },
            annotations: { 'prometheus.io/scrape': 'true' }
        }); }); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'test'; });
        expect(template.metadata).toBeDefined();
        expect(template.metadata.labels).toEqual({ app: 'test', tier: 'backend' });
        expect(template.metadata.annotations).toEqual({ 'prometheus.io/scrape': 'true' });
    });
    it('should render pod metadata with expressions from inputs', function () {
        var wf = src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-metadata-expr',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            .addRequiredInput('jobId', (0, src_1.typeToken)())
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addPodMetadata(function (_a) {
            var inputs = _a.inputs;
            return ({
                labels: { 'job-id': inputs.jobId }
            });
        }); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'test'; });
        expect(template.metadata.labels['job-id']).toBe('{{inputs.parameters.jobId}}');
    });
    it('should reject duplicate addPodMetadata calls at compile time', function () {
        src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-metadata',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            // @ts-expect-error - duplicate addPodMetadata should be rejected (error surfaces at addContainer level)
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addPodMetadata(function () { return ({ labels: { first: 'call' } }); })
            .addPodMetadata(function () { return ({ labels: { second: 'call' } }); }); }); });
        expect(true).toBe(true);
    });
});
describe('Pod Config - Tolerations', function () {
    it('should render tolerations with static values', function () {
        var wf = src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-tolerations',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addTolerations(function () { return [
            { key: 'dedicated', operator: 'Equal', value: 'gpu', effect: 'NoSchedule' }
        ]; }); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'test'; });
        expect(template.tolerations).toBeDefined();
        expect(template.tolerations).toHaveLength(1);
        expect(template.tolerations[0]).toEqual({
            key: 'dedicated',
            operator: 'Equal',
            value: 'gpu',
            effect: 'NoSchedule'
        });
    });
    it('should render tolerations with expressions from workflow inputs', function () {
        var wf = src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-tolerations-expr',
            serviceAccountName: 'default'
        })
            .addParams({
            nodePool: (0, src_1.defineParam)({ expression: 'default' })
        })
            .addTemplate('test', function (t) { return t
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addTolerations(function (_a) {
            var workflowInputs = _a.workflowInputs;
            return [
                { key: 'pool', operator: 'Equal', value: workflowInputs.nodePool }
            ];
        }); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'test'; });
        expect(template.tolerations[0].value).toBe('{{workflow.parameters.nodePool}}');
    });
    it('should reject duplicate addTolerations calls at compile time', function () {
        src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-tolerations',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            // @ts-expect-error - duplicate addTolerations should be rejected (error surfaces at addContainer level)
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addTolerations(function () { return []; })
            .addTolerations(function () { return []; }); }); });
        expect(true).toBe(true);
    });
});
describe('Pod Config - NodeSelector', function () {
    it('should render nodeSelector with static values', function () {
        var wf = src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-nodeselector',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addNodeSelector(function () { return ({
            'kubernetes.io/arch': 'amd64',
            'node-type': 'compute'
        }); }); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'test'; });
        expect(template.nodeSelector).toEqual({
            'kubernetes.io/arch': 'amd64',
            'node-type': 'compute'
        });
    });
    it('should reject duplicate addNodeSelector calls at compile time', function () {
        src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-nodeselector',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            // @ts-expect-error - duplicate addNodeSelector should be rejected (error surfaces at addContainer level)
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addNodeSelector(function () { return ({ 'node-type': 'first' }); })
            .addNodeSelector(function () { return ({ 'node-type': 'second' }); }); }); });
        expect(true).toBe(true);
    });
});
describe('Pod Config - ActiveDeadlineSeconds', function () {
    it('should render activeDeadlineSeconds with static value', function () {
        var wf = src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-deadline',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addActiveDeadlineSeconds(function () { return 3600; }); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'test'; });
        expect(template.activeDeadlineSeconds).toBe(3600);
    });
    it('should render activeDeadlineSeconds with string duration', function () {
        var wf = src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-deadline-string',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addActiveDeadlineSeconds(function () { return "1h"; }); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'test'; });
        expect(template.activeDeadlineSeconds).toBe("1h");
    });
    it('should reject duplicate addActiveDeadlineSeconds calls at compile time', function () {
        src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-deadline',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            // @ts-expect-error - duplicate addActiveDeadlineSeconds should be rejected (error surfaces at addContainer level)
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addActiveDeadlineSeconds(function () { return 100; })
            .addActiveDeadlineSeconds(function () { return 200; }); }); });
        expect(true).toBe(true);
    });
});
describe('Pod Config - Combined', function () {
    it('should allow all pod config methods to be called once each', function () {
        var wf = src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-combined',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addPodMetadata(function () { return ({ labels: { app: 'test' } }); })
            .addTolerations(function () { return [{ key: 'dedicated', operator: 'Exists' }]; })
            .addNodeSelector(function () { return ({ 'node-type': 'compute' }); })
            .addActiveDeadlineSeconds(function () { return 3600; }); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'test'; });
        expect(template.metadata.labels).toEqual({ app: 'test' });
        expect(template.tolerations).toHaveLength(1);
        expect(template.nodeSelector).toEqual({ 'node-type': 'compute' });
        expect(template.activeDeadlineSeconds).toBe(3600);
    });
    it('should allow pod config methods in any order', function () {
        var wf = src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-order',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addActiveDeadlineSeconds(function () { return 1800; })
            .addCommand(['echo'])
            .addNodeSelector(function () { return ({ tier: 'backend' }); })
            .addResources(EXAMPLE_RESOURCES)
            .addTolerations(function () { return []; })
            .addPodMetadata(function () { return ({ annotations: { note: 'test' } }); }); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'test'; });
        expect(template.activeDeadlineSeconds).toBe(1800);
        expect(template.nodeSelector).toEqual({ tier: 'backend' });
        expect(template.metadata.annotations).toEqual({ note: 'test' });
    });
});
describe('Pod Config - Affinity', function () {
    it('should render node affinity', function () {
        var wf = src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-affinity',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addAffinity(function () { return ({
            nodeAffinity: {
                requiredDuringSchedulingIgnoredDuringExecution: {
                    nodeSelectorTerms: [{
                            matchExpressions: [{
                                    key: 'kubernetes.io/arch',
                                    operator: 'In',
                                    values: ['amd64']
                                }]
                        }]
                }
            }
        }); }); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'test'; });
        expect(template.affinity).toBeDefined();
        expect(template.affinity.nodeAffinity).toBeDefined();
    });
    it('should reject duplicate addAffinity calls at compile time', function () {
        src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-affinity',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            // @ts-expect-error - duplicate addAffinity should be rejected
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addAffinity(function () { return ({}); })
            .addAffinity(function () { return ({}); }); }); });
        expect(true).toBe(true);
    });
});
describe('Pod Config - SchedulerName', function () {
    it('should render schedulerName', function () {
        var wf = src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-scheduler',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addSchedulerName(function () { return 'custom-scheduler'; }); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'test'; });
        expect(template.schedulerName).toBe('custom-scheduler');
    });
    it('should reject duplicate addSchedulerName calls at compile time', function () {
        src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-scheduler',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            // @ts-expect-error - duplicate addSchedulerName should be rejected
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addSchedulerName(function () { return 'first'; })
            .addSchedulerName(function () { return 'second'; }); }); });
        expect(true).toBe(true);
    });
});
describe('Pod Config - PriorityClassName', function () {
    it('should render priorityClassName', function () {
        var wf = src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-priority',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addPriorityClassName(function () { return 'high-priority'; }); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'test'; });
        expect(template.priorityClassName).toBe('high-priority');
    });
    it('should reject duplicate addPriorityClassName calls at compile time', function () {
        src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-priority',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            // @ts-expect-error - duplicate addPriorityClassName should be rejected
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addPriorityClassName(function () { return 'first'; })
            .addPriorityClassName(function () { return 'second'; }); }); });
        expect(true).toBe(true);
    });
});
describe('Pod Config - ServiceAccountName', function () {
    it('should render serviceAccountName', function () {
        var wf = src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-sa',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addServiceAccountName(function () { return 'my-service-account'; }); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'test'; });
        expect(template.serviceAccountName).toBe('my-service-account');
    });
    it('should reject duplicate addServiceAccountName calls at compile time', function () {
        src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-sa',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            // @ts-expect-error - duplicate addServiceAccountName should be rejected
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addServiceAccountName(function () { return 'first'; })
            .addServiceAccountName(function () { return 'second'; }); }); });
        expect(true).toBe(true);
    });
});
describe('Pod Config - AutomountServiceAccountToken', function () {
    it('should render automountServiceAccountToken', function () {
        var wf = src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-automount',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addAutomountServiceAccountToken(function () { return false; }); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'test'; });
        expect(template.automountServiceAccountToken).toBe(false);
    });
    it('should reject duplicate addAutomountServiceAccountToken calls at compile time', function () {
        src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-automount',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            // @ts-expect-error - duplicate addAutomountServiceAccountToken should be rejected
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addAutomountServiceAccountToken(function () { return true; })
            .addAutomountServiceAccountToken(function () { return false; }); }); });
        expect(true).toBe(true);
    });
});
describe('Pod Config - SecurityContext', function () {
    it('should render securityContext', function () {
        var wf = src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-security',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addSecurityContext(function () { return ({
            runAsUser: 1000,
            runAsGroup: 1000,
            runAsNonRoot: true,
            fsGroup: 2000
        }); }); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'test'; });
        expect(template.securityContext).toEqual({
            runAsUser: 1000,
            runAsGroup: 1000,
            runAsNonRoot: true,
            fsGroup: 2000
        });
    });
    it('should reject duplicate addSecurityContext calls at compile time', function () {
        src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-security',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            // @ts-expect-error - duplicate addSecurityContext should be rejected
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addSecurityContext(function () { return ({ runAsUser: 1000 }); })
            .addSecurityContext(function () { return ({ runAsUser: 2000 }); }); }); });
        expect(true).toBe(true);
    });
});
describe('Pod Config - HostAliases', function () {
    it('should render hostAliases', function () {
        var wf = src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-hostalias',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addHostAliases(function () { return [
            { ip: '127.0.0.1', hostnames: ['myhost.local', 'myhost'] }
        ]; }); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'test'; });
        expect(template.hostAliases).toEqual([
            { ip: '127.0.0.1', hostnames: ['myhost.local', 'myhost'] }
        ]);
    });
    it('should reject duplicate addHostAliases calls at compile time', function () {
        src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-hostalias',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            // @ts-expect-error - duplicate addHostAliases should be rejected
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addHostAliases(function () { return []; })
            .addHostAliases(function () { return []; }); }); });
        expect(true).toBe(true);
    });
});
describe('Pod Config - PodSpecPatch', function () {
    it('should render podSpecPatch', function () {
        var wf = src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-patch',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addPodSpecPatch(function () { return '{"terminationGracePeriodSeconds": 30}'; }); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'test'; });
        expect(template.podSpecPatch).toBe('{"terminationGracePeriodSeconds": 30}');
    });
    it('should reject duplicate addPodSpecPatch calls at compile time', function () {
        src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-patch',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            // @ts-expect-error - duplicate addPodSpecPatch should be rejected
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addPodSpecPatch(function () { return '{}'; })
            .addPodSpecPatch(function () { return '{}'; }); }); });
        expect(true).toBe(true);
    });
});
describe('Pod Config - All Features Combined', function () {
    it('should allow all pod config methods to be called once each', function () {
        var wf = src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-all-combined',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addPodMetadata(function () { return ({ labels: { app: 'test' } }); })
            .addTolerations(function () { return [{ key: 'dedicated', operator: 'Exists' }]; })
            .addNodeSelector(function () { return ({ 'node-type': 'compute' }); })
            .addActiveDeadlineSeconds(function () { return 3600; })
            .addAffinity(function () { return ({ nodeAffinity: {} }); })
            .addSchedulerName(function () { return 'custom-scheduler'; })
            .addPriorityClassName(function () { return 'high-priority'; })
            .addServiceAccountName(function () { return 'my-sa'; })
            .addAutomountServiceAccountToken(function () { return false; })
            .addSecurityContext(function () { return ({ runAsNonRoot: true }); })
            .addHostAliases(function () { return [{ ip: '10.0.0.1', hostnames: ['db'] }]; })
            .addPodSpecPatch(function () { return '{}'; }); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'test'; });
        expect(template.metadata.labels).toEqual({ app: 'test' });
        expect(template.tolerations).toHaveLength(1);
        expect(template.nodeSelector).toEqual({ 'node-type': 'compute' });
        expect(template.activeDeadlineSeconds).toBe(3600);
        expect(template.affinity).toBeDefined();
        expect(template.schedulerName).toBe('custom-scheduler');
        expect(template.priorityClassName).toBe('high-priority');
        expect(template.serviceAccountName).toBe('my-sa');
        expect(template.automountServiceAccountToken).toBe(false);
        expect(template.securityContext).toEqual({ runAsNonRoot: true });
        expect(template.hostAliases).toHaveLength(1);
        expect(template.podSpecPatch).toBe('{}');
    });
});
describe('Pod Config - RetryStrategy', function () {
    it('should render retryStrategy', function () {
        var wf = src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-retry',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addRetryParameters({ limit: 3, retryPolicy: 'OnFailure' }); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'test'; });
        expect(template.retryStrategy).toEqual({ limit: 3, retryPolicy: 'OnFailure' });
    });
    it('should reject duplicate addRetryParameters calls at compile time', function () {
        src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-retry',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            // @ts-expect-error - duplicate addRetryParameters should be rejected
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addRetryParameters({ limit: 3 })
            .addRetryParameters({ limit: 5 }); }); });
        expect(true).toBe(true);
    });
});
describe('Pod Config - Synchronization', function () {
    it('should render synchronization', function () {
        var wf = src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-sync',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addSynchronization(function () { return ({
            mutexes: [{ name: 'my-mutex' }]
        }); }); }); })
            .getFullScope();
        var rendered = (0, src_1.renderWorkflowTemplate)(wf);
        var template = rendered.spec.templates.find(function (t) { return t.name === 'test'; });
        expect(template.synchronization).toBeDefined();
        expect(template.synchronization.mutexes).toBeDefined();
        expect(template.synchronization.mutexes[0].name).toBe('my-mutex');
    });
    it('should reject duplicate addSynchronization calls at compile time', function () {
        src_1.WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-sync',
            serviceAccountName: 'default'
        })
            .addTemplate('test', function (t) { return t
            // @ts-expect-error - duplicate addSynchronization should be rejected
            .addContainer(function (c) { return c
            .addImageInfo('nginx:latest', 'IfNotPresent')
            .addCommand(['echo'])
            .addResources(EXAMPLE_RESOURCES)
            .addSynchronization(function () { return ({ mutexes: [{ name: 'first' }] }); })
            .addSynchronization(function () { return ({ mutexes: [{ name: 'second' }] }); }); }); });
        expect(true).toBe(true);
    });
});
