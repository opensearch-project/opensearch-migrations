import { WorkflowBuilder, expr, typeToken } from '../src';
import { DEFAULT_RESOURCES } from '@opensearch-migrations/schemas';

describe('Resource Enforcement', () => {
    it('should allow container with resources', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-workflow',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo', 'hello'])
                .addResources(DEFAULT_RESOURCES.MIGRATION_CONSOLE_CLI)
                .addArgs(['world'])
            )
        )
        .getFullScope();
        
        expect(wf).toBeDefined();
        expect(wf.templates.test).toBeDefined();
    });

    it('should throw runtime error for container without resources', () => {
        expect(() => {
            WorkflowBuilder.create({
                k8sResourceName: 'test-workflow',
                serviceAccountName: 'default'
            })
            .addTemplate('test', t => t
                .addContainer(c => c
                    .addImageInfo('nginx:latest', 'IfNotPresent')
                    .addCommand(['echo', 'hello'])
                    .addArgs(['world'])
                    // Missing .addResources() - should throw runtime error
                )
            );
        }).toThrow('Container resources must be specified using addResources() before finalizing the template');
    });

    it('should merge multiple resource specifications', () => {
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
                    limits: { 'ephemeral-storage': '10Gi' },
                    requests: { 'ephemeral-storage': '5Gi' }
                })
                .addCommand(['echo', 'hello'])
            )
        )
        .getFullScope();
        
        expect(wf).toBeDefined();
        expect(wf.templates.test).toBeDefined();
    });

    it('should work with expression-based resources', () => {
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
                .addResources(expr.literal({
                    limits: { cpu: '1000m', memory: '1Gi' },
                    requests: { cpu: '500m', memory: '512Mi' }
                }))
                .addCommand(['echo', 'hello'])
            )
        )
        .getFullScope();
        
        expect(wf).toBeDefined();
    });

    it('should allow resources to be added at any point in the chain', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-workflow',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addCommand(['echo', 'hello'])
                .addArgs(['world'])
                .addResources(DEFAULT_RESOURCES.MIGRATION_CONSOLE_CLI)
                // Resources added after other methods - should still work
            )
        );
        
        expect(wf).toBeDefined();
    });

    it('should work with environment variables and resources', () => {
        const wf = WorkflowBuilder.create({
            k8sResourceName: 'test-workflow',
            serviceAccountName: 'default'
        })
        .addTemplate('test', t => t
            .addContainer(c => c
                .addImageInfo('nginx:latest', 'IfNotPresent')
                .addEnvVar('MY_VAR', expr.literal('value'))
                .addResources(DEFAULT_RESOURCES.MIGRATION_CONSOLE_CLI)
                .addCommand(['echo', 'hello'])
            )
        );
        
        expect(wf).toBeDefined();
    });
});
