import { renderWorkflowTemplate, typeToken, WorkflowBuilder } from '../../src';

describe('WaitForExistingResource retry support', () => {
    it('renders retryStrategy when addRetryParameters is called', () => {
        const retryParams = {
            limit: "6",
            retryPolicy: "Always",
            backoff: { duration: "10", factor: "2", cap: "60" }
        };

        const wf = WorkflowBuilder.create({ k8sResourceName: 'test-wait-retry' })
            .addTemplate('waitWithRetry', t => t
                .addRequiredInput('resourceName', typeToken<string>())
                .addWaitForExistingResource(b => b
                    .setDefinition({
                        resource: {
                            apiVersion: "migrations.opensearch.org/v1alpha1",
                            kind: "DataSnapshot",
                            name: b.inputs.resourceName
                        },
                        conditions: { successCondition: "status.phase == Ready" }
                    })
                    .addRetryParameters(retryParams)
                )
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'waitwithretry');

        expect(template).toBeDefined();
        expect(template.retryStrategy).toEqual(retryParams);
        expect(template.resource).toBeDefined();
        expect(template.resource.successCondition).toBe("status.phase == Ready");
    });

    it('omits retryStrategy when addRetryParameters is not called', () => {
        const wf = WorkflowBuilder.create({ k8sResourceName: 'test-wait-no-retry' })
            .addTemplate('waitNoRetry', t => t
                .addRequiredInput('resourceName', typeToken<string>())
                .addWaitForExistingResource(b => b
                    .setDefinition({
                        resource: {
                            apiVersion: "v1",
                            kind: "Pod",
                            name: b.inputs.resourceName
                        },
                        conditions: { successCondition: "status.phase == Running" }
                    })
                )
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'waitnoretry');

        expect(template).toBeDefined();
        expect(template.retryStrategy).toBeUndefined();
    });
});
