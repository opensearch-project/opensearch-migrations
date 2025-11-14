import { WorkflowBuilder, expr, renderWorkflowTemplate } from '../src';

const EXAMPLE_RESOURCES = {
    requests: {
        cpu: "1000m",
        memory: "1024m"
    },
    limits: {
        cpu: "1000m",
        memory: "1024m"
    }
}

describe('Resource Enforcement', () => {
    it('should allow container with resources and verify resources block', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-workflow',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo', 'hello'])
                .addResources(EXAMPLE_RESOURCES)
                .addArgs(['world'])
            )
        )
        .getFullScope();
        
        expect(wf).toBeDefined();
        expect(wf.templates.test).toBeDefined();
        
        // Verify the resources are in the body scope
        expect(wf.templates.test.body.container.resources).toBeDefined();
        
        // Render the workflow and verify resources in the final output
        const rendered = renderWorkflowTemplate(wf);
        const testTemplate = rendered.spec.templates.find((t: any) => t.name === 'test');
        
        expect(testTemplate).toBeDefined();
        expect(testTemplate.container).toBeDefined();
        expect(testTemplate.container.resources).toBeDefined();
        
        // Resources cannot yet be rendered as Argo template expressions
        // expect(testTemplate.container.resources).toBe(
        //     '{{=sprig.merge({}, {"requests":{"cpu":"1000m","memory":"1024m"},"limits":{"cpu":"1000m","memory":"1024m"}})}}'
        // );
        expect(testTemplate.container.resources).toStrictEqual(EXAMPLE_RESOURCES);
    });

    it('should show compile-time error for container without resources', () => {
        // NOTE: This test demonstrates that TypeScript shows a compile-time error
        // when resources are missing. The @ts-expect-error directive below confirms
        // that TypeScript correctly identifies the missing resources at compile-time.
        //
        // This is a COMPILE-TIME check using branded types - the code shows
        // red squiggles in your IDE before you even run the code!
        
        WorkflowBuilder.create({
            k8sResourceName: 'test-workflow',
            serviceAccountName: 'default'
        })
        .addTemplate('test', (
            t => t.addContainer(
                // @ts-expect-error - addResources() is missing causing compile error
                c => c
                    .addImageInfo('nginx:latest', 'IfNotPresent')
                    .addCommand(['echo', 'hello'])
                    .addArgs(['world'])
            )
        ));
        
        // The @ts-expect-error above proves the type system is working correctly
        // If you remove it, you'll see the red squiggles in your IDE
        expect(true).toBe(true);
    });

    it.skip('should merge multiple resource specifications and verify merged result', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-workflow',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addResources({
                    limits: { cpu: '1000m', memory: '1Gi' },
                    requests: { cpu: '500m', memory: '512Mi' }
                })
                .addResources({
                    limits: { cpu: '999m', 'ephemeral-storage': '10Gi' },
                    requests: { 'ephemeral-storage': '5Gi' }
                })
                .addCommand(['echo', 'hello'])
            )
        )
        .getFullScope();
        
        expect(wf).toBeDefined();
        expect(wf.templates.test).toBeDefined();
        
        // Render and verify merged resources - the merge expression combines both resource specs
        const rendered = renderWorkflowTemplate(wf);
        const testTemplate = rendered.spec.templates.find((t: any) => t.name === 'test');
        
        expect(testTemplate.container.resources).toBeDefined();

        // Verify the expression matches the expected merged resources string
        const resourcesStr = testTemplate.container.resources;

        const expected = '{{=sprig.merge({"kind":"function","functionName":"sprig.merge","args":[' +
            '{"kind":"literal","value":{}},' +
            '{"kind":"literal","value":{"limits":{"cpu":"1000m","memory":"1Gi"},"requests":{"cpu":"500m","memory":"512Mi"}}}' +
            ']},' +
            '{"limits":{"cpu":"999m","ephemeral-storage":"10Gi"},"requests":{"ephemeral-storage":"5Gi"}})}}';

        expect(resourcesStr).toStrictEqual(expected);
    });

    it.skip('should work with expression-based resources and verify in output', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-workflow',
            serviceAccountName: 'default'
        })
        .addParams({
            cpuLimit: { description: 'CPU limit' } as any
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addResources(expr.literal(EXAMPLE_RESOURCES))
                .addCommand(['echo', 'hello'])
            )
        )
        .getFullScope();
        
        expect(wf).toBeDefined();
        expect(wf.templates.test).toBeDefined();
        
        // Verify the resources are in the body scope
        expect(wf.templates.test.body.container.resources).toBeDefined();
        
        // Render the workflow and verify resources in the final output
        const rendered = renderWorkflowTemplate(wf);
        const testTemplate = rendered.spec.templates.find((t: any) => t.name === 'test');
        
        expect(testTemplate).toBeDefined();
        expect(testTemplate.container).toBeDefined();
        expect(testTemplate.container.resources).toBeDefined();

        expect(testTemplate.container.resources).toStrictEqual(EXAMPLE_RESOURCES);
    });
});
