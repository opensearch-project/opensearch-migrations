import { WorkflowBuilder, renderWorkflowTemplate, typeToken, defineParam } from '../../src';

const EXAMPLE_RESOURCES = {
    requests: { cpu: "100m", memory: "128Mi" },
    limits: { cpu: "200m", memory: "256Mi" }
};

describe('Pod Config - Metadata', () => {
    it('should render pod metadata with static labels and annotations', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-metadata',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addPodMetadata(() => ({
                    labels: { app: 'test', tier: 'backend' },
                    annotations: { 'prometheus.io/scrape': 'true' }
                }))
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');

        expect(template.metadata).toBeDefined();
        expect(template.metadata.labels).toEqual({ app: 'test', tier: 'backend' });
        expect(template.metadata.annotations).toEqual({ 'prometheus.io/scrape': 'true' });
    });

    it('should render pod metadata with expressions from inputs', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-metadata-expr',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addRequiredInput('jobId', typeToken<string>())
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addPodMetadata(({ inputs }) => ({
                    labels: { 'job-id': inputs.jobId }
                }))
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');

        expect(template.metadata.labels['job-id']).toBe('{{inputs.parameters.jobId}}');
    });

    it('should reject duplicate addPodMetadata calls at compile time', () => {
        WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-metadata',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            // @ts-expect-error - duplicate addPodMetadata should be rejected (error surfaces at addContainer level)
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addPodMetadata(() => ({ labels: { first: 'call' } }))
                .addPodMetadata(() => ({ labels: { second: 'call' } }))
            )
        );

        expect(true).toBe(true);
    });
});

describe('Pod Config - Tolerations', () => {
    it('should render tolerations with static values', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-tolerations',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addTolerations(() => [
                    { key: 'dedicated', operator: 'Equal', value: 'gpu', effect: 'NoSchedule' }
                ])
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');

        expect(template.tolerations).toBeDefined();
        expect(template.tolerations).toHaveLength(1);
        expect(template.tolerations[0]).toEqual({
            key: 'dedicated',
            operator: 'Equal',
            value: 'gpu',
            effect: 'NoSchedule'
        });
    });

    it('should render tolerations with expressions from workflow inputs', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-tolerations-expr',
            serviceAccountName: 'default'
        })
        .addParams({
            nodePool: defineParam({ expression: 'default' })
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addTolerations(({ workflowInputs }) => [
                    { key: 'pool', operator: 'Equal', value: workflowInputs.nodePool }
                ])
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');

        expect(template.tolerations[0].value).toBe('{{workflow.parameters.nodePool}}');
    });

    it('should reject duplicate addTolerations calls at compile time', () => {
        WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-tolerations',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            // @ts-expect-error - duplicate addTolerations should be rejected (error surfaces at addContainer level)
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addTolerations(() => [])
                .addTolerations(() => [])
            )
        );

        expect(true).toBe(true);
    });
});

describe('Pod Config - NodeSelector', () => {
    it('should render nodeSelector with static values', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-nodeselector',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addNodeSelector(() => ({
                    'kubernetes.io/arch': 'amd64',
                    'node-type': 'compute'
                }))
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');

        expect(template.nodeSelector).toEqual({
            'kubernetes.io/arch': 'amd64',
            'node-type': 'compute'
        });
    });

    it('should reject duplicate addNodeSelector calls at compile time', () => {
        WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-nodeselector',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            // @ts-expect-error - duplicate addNodeSelector should be rejected (error surfaces at addContainer level)
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addNodeSelector(() => ({ 'node-type': 'first' }))
                .addNodeSelector(() => ({ 'node-type': 'second' }))
            )
        );

        expect(true).toBe(true);
    });
});

describe('Pod Config - ActiveDeadlineSeconds', () => {
    it('should render activeDeadlineSeconds with static value', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-deadline',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addActiveDeadlineSeconds(() => 3600)
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');

        expect(template.activeDeadlineSeconds).toBe(3600);
    });

    it('should render activeDeadlineSeconds with string duration', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-deadline-string',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addActiveDeadlineSeconds(() => "1h")
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');

        expect(template.activeDeadlineSeconds).toBe("1h");
    });

    it('should reject duplicate addActiveDeadlineSeconds calls at compile time', () => {
        WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-deadline',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            // @ts-expect-error - duplicate addActiveDeadlineSeconds should be rejected (error surfaces at addContainer level)
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addActiveDeadlineSeconds(() => 100)
                .addActiveDeadlineSeconds(() => 200)
            )
        );

        expect(true).toBe(true);
    });
});

describe('Pod Config - Combined', () => {
    it('should allow all pod config methods to be called once each', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-combined',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addPodMetadata(() => ({ labels: { app: 'test' } }))
                .addTolerations(() => [{ key: 'dedicated', operator: 'Exists' }])
                .addNodeSelector(() => ({ 'node-type': 'compute' }))
                .addActiveDeadlineSeconds(() => 3600)
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');

        expect(template.metadata.labels).toEqual({ app: 'test' });
        expect(template.tolerations).toHaveLength(1);
        expect(template.nodeSelector).toEqual({ 'node-type': 'compute' });
        expect(template.activeDeadlineSeconds).toBe(3600);
    });

    it('should allow pod config methods in any order', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-order',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addActiveDeadlineSeconds(() => 1800)
                .addCommand(['echo'])
                .addNodeSelector(() => ({ tier: 'backend' }))
                .addResources(EXAMPLE_RESOURCES)
                .addTolerations(() => [])
                .addPodMetadata(() => ({ annotations: { note: 'test' } }))
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');

        expect(template.activeDeadlineSeconds).toBe(1800);
        expect(template.nodeSelector).toEqual({ tier: 'backend' });
        expect(template.metadata.annotations).toEqual({ note: 'test' });
    });
});


describe('Pod Config - Affinity', () => {
    it('should render node affinity', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-affinity',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addAffinity(() => ({
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
                }))
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');

        expect(template.affinity).toBeDefined();
        expect(template.affinity.nodeAffinity).toBeDefined();
    });

    it('should reject duplicate addAffinity calls at compile time', () => {
        WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-affinity',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            // @ts-expect-error - duplicate addAffinity should be rejected
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addAffinity(() => ({}))
                .addAffinity(() => ({}))
            )
        );
        expect(true).toBe(true);
    });
});

describe('Pod Config - SchedulerName', () => {
    it('should render schedulerName', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-scheduler',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addSchedulerName(() => 'custom-scheduler')
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');

        expect(template.schedulerName).toBe('custom-scheduler');
    });

    it('should reject duplicate addSchedulerName calls at compile time', () => {
        WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-scheduler',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            // @ts-expect-error - duplicate addSchedulerName should be rejected
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addSchedulerName(() => 'first')
                .addSchedulerName(() => 'second')
            )
        );
        expect(true).toBe(true);
    });
});

describe('Pod Config - PriorityClassName', () => {
    it('should render priorityClassName', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-priority',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addPriorityClassName(() => 'high-priority')
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');

        expect(template.priorityClassName).toBe('high-priority');
    });

    it('should reject duplicate addPriorityClassName calls at compile time', () => {
        WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-priority',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            // @ts-expect-error - duplicate addPriorityClassName should be rejected
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addPriorityClassName(() => 'first')
                .addPriorityClassName(() => 'second')
            )
        );
        expect(true).toBe(true);
    });
});


describe('Pod Config - ServiceAccountName', () => {
    it('should render serviceAccountName', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-sa',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addServiceAccountName(() => 'my-service-account')
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');

        expect(template.serviceAccountName).toBe('my-service-account');
    });

    it('should reject duplicate addServiceAccountName calls at compile time', () => {
        WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-sa',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            // @ts-expect-error - duplicate addServiceAccountName should be rejected
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addServiceAccountName(() => 'first')
                .addServiceAccountName(() => 'second')
            )
        );
        expect(true).toBe(true);
    });
});

describe('Pod Config - AutomountServiceAccountToken', () => {
    it('should render automountServiceAccountToken', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-automount',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addAutomountServiceAccountToken(() => false)
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');

        expect(template.automountServiceAccountToken).toBe(false);
    });

    it('should reject duplicate addAutomountServiceAccountToken calls at compile time', () => {
        WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-automount',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            // @ts-expect-error - duplicate addAutomountServiceAccountToken should be rejected
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addAutomountServiceAccountToken(() => true)
                .addAutomountServiceAccountToken(() => false)
            )
        );
        expect(true).toBe(true);
    });
});

describe('Pod Config - SecurityContext', () => {
    it('should render securityContext', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-security',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addSecurityContext(() => ({
                    runAsUser: 1000,
                    runAsGroup: 1000,
                    runAsNonRoot: true,
                    fsGroup: 2000
                }))
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');

        expect(template.securityContext).toEqual({
            runAsUser: 1000,
            runAsGroup: 1000,
            runAsNonRoot: true,
            fsGroup: 2000
        });
    });

    it('should reject duplicate addSecurityContext calls at compile time', () => {
        WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-security',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            // @ts-expect-error - duplicate addSecurityContext should be rejected
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addSecurityContext(() => ({ runAsUser: 1000 }))
                .addSecurityContext(() => ({ runAsUser: 2000 }))
            )
        );
        expect(true).toBe(true);
    });
});

describe('Pod Config - HostAliases', () => {
    it('should render hostAliases', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-hostalias',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addHostAliases(() => [
                    { ip: '127.0.0.1', hostnames: ['myhost.local', 'myhost'] }
                ])
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');

        expect(template.hostAliases).toEqual([
            { ip: '127.0.0.1', hostnames: ['myhost.local', 'myhost'] }
        ]);
    });

    it('should reject duplicate addHostAliases calls at compile time', () => {
        WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-hostalias',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            // @ts-expect-error - duplicate addHostAliases should be rejected
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addHostAliases(() => [])
                .addHostAliases(() => [])
            )
        );
        expect(true).toBe(true);
    });
});

describe('Pod Config - PodSpecPatch', () => {
    it('should render podSpecPatch', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-patch',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addPodSpecPatch(() => '{"terminationGracePeriodSeconds": 30}')
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');

        expect(template.podSpecPatch).toBe('{"terminationGracePeriodSeconds": 30}');
    });

    it('should reject duplicate addPodSpecPatch calls at compile time', () => {
        WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-patch',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            // @ts-expect-error - duplicate addPodSpecPatch should be rejected
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addPodSpecPatch(() => '{}')
                .addPodSpecPatch(() => '{}')
            )
        );
        expect(true).toBe(true);
    });
});

describe('Pod Config - All Features Combined', () => {
    it('should allow all pod config methods to be called once each', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-all-combined',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addPodMetadata(() => ({ labels: { app: 'test' } }))
                .addTolerations(() => [{ key: 'dedicated', operator: 'Exists' }])
                .addNodeSelector(() => ({ 'node-type': 'compute' }))
                .addActiveDeadlineSeconds(() => 3600)
                .addAffinity(() => ({ nodeAffinity: {} }))
                .addSchedulerName(() => 'custom-scheduler')
                .addPriorityClassName(() => 'high-priority')
                .addServiceAccountName(() => 'my-sa')
                .addAutomountServiceAccountToken(() => false)
                .addSecurityContext(() => ({ runAsNonRoot: true }))
                .addHostAliases(() => [{ ip: '10.0.0.1', hostnames: ['db'] }])
                .addPodSpecPatch(() => '{}')
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');

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


describe('Pod Config - RetryStrategy', () => {
    it('should render retryStrategy', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-retry',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addRetryParameters({ limit: 3, retryPolicy: 'OnFailure' })
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');

        expect(template.retryStrategy).toEqual({ limit: 3, retryPolicy: 'OnFailure' });
    });

    it('should reject duplicate addRetryParameters calls at compile time', () => {
        WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-retry',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            // @ts-expect-error - duplicate addRetryParameters should be rejected
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addRetryParameters({ limit: 3 })
                .addRetryParameters({ limit: 5 })
            )
        );
        expect(true).toBe(true);
    });
});

describe('Pod Config - Synchronization', () => {
    it('should render synchronization', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-sync',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addSynchronization(() => ({
                    mutexes: [{ name: 'my-mutex' }]
                }))
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');

        expect(template.synchronization).toBeDefined();
        expect(template.synchronization.mutexes).toBeDefined();
        expect(template.synchronization.mutexes[0].name).toBe('my-mutex');
    });

    it('should reject duplicate addSynchronization calls at compile time', () => {
        WorkflowBuilder.create({
            k8sResourceName: 'test-duplicate-sync',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            // @ts-expect-error - duplicate addSynchronization should be rejected
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addSynchronization(() => ({ mutexes: [{ name: 'first' }] }))
                .addSynchronization(() => ({ mutexes: [{ name: 'second' }] }))
            )
        );
        expect(true).toBe(true);
    });
});
