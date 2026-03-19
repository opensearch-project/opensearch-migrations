import { WorkflowBuilder, renderWorkflowTemplate } from '../../src';

const EXAMPLE_RESOURCES = {
    requests: { cpu: "100m", memory: "128Mi" },
    limits: { cpu: "200m", memory: "256Mi" }
};

describe('Artifact Outputs', () => {
    it('should render artifact output in template', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-artifact',
            serviceAccountName: 'sa'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('alpine', 'IfNotPresent')
                .addCommand(['echo', 'hello'])
                .addResources(EXAMPLE_RESOURCES)
                .addArtifactOutput('result', '/tmp/result.txt')
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');
        expect(template.outputs.artifacts).toEqual([
            { name: 'result', path: '/tmp/result.txt', archive: { none: {} } }
        ]);
    });

    it('should render both parameter and artifact outputs', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-mixed',
            serviceAccountName: 'sa'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('alpine', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addPathOutput('phase', '/tmp/phase.txt', {} as any)
                .addArtifactOutput('statusOutput', '/tmp/status-output.txt')
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');
        expect(template.outputs.parameters).toEqual([
            { name: 'phase', valueFrom: { path: '/tmp/phase.txt' } }
        ]);
        expect(template.outputs.artifacts).toEqual([
            { name: 'statusOutput', path: '/tmp/status-output.txt', archive: { none: {} } }
        ]);
    });

    it('should not render artifacts key when no artifact outputs exist', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-no-artifacts',
            serviceAccountName: 'sa'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('alpine', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');
        expect(template.outputs.artifacts).toBeUndefined();
    });

    it('should render multiple artifact outputs', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-multi-artifact',
            serviceAccountName: 'sa'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('alpine', 'IfNotPresent')
                .addCommand(['echo'])
                .addResources(EXAMPLE_RESOURCES)
                .addArtifactOutput('output1', '/tmp/out1.txt')
                .addArtifactOutput('output2', '/tmp/out2.txt')
            )
        )
        .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');
        expect(template.outputs.artifacts).toHaveLength(2);
        expect(template.outputs.artifacts).toEqual(
            expect.arrayContaining([
                { name: 'output1', path: '/tmp/out1.txt', archive: { none: {} } },
                { name: 'output2', path: '/tmp/out2.txt', archive: { none: {} } }
            ])
        );
    });
});
