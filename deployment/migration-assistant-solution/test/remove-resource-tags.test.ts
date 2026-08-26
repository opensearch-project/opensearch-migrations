import { describe, expect, test } from '@jest/globals';
import { App } from 'aws-cdk-lib';
import { Template } from 'aws-cdk-lib/assertions';
import { FUNCTIONAL_TAG_KEY_PREFIXES } from '../lib/remove-resource-tags';
import { SolutionsInfrastructureStack } from '../lib/solutions-stack';
import { SolutionsInfrastructureEKSStack } from '../lib/solutions-stack-eks';

const defaultProperties = {
    solutionId: 'SO0000',
    solutionName: 'test-solution',
    solutionVersion: '0.0.1',
    codeBucket: 'test-bucket',
    createVPC: true,
    env: {
        region: 'us-west-1'
    }
};

/**
 * Collects the tag keys rendered into a template, per resource type. Tag keys that are CloudFormation
 * intrinsics rather than literals (the EKS `kubernetes.io/cluster/<name>` tag interpolates a
 * parameter) are reported as `<intrinsic>`.
 */
function tagKeysByResourceType(template: Template): Record<string, Set<string>> {
    const found: Record<string, Set<string>> = {};
    const resources = template.toJSON().Resources ?? {};
    for (const resource of Object.values(resources) as { Type: string, Properties?: Record<string, unknown> }[]) {
        const tags = resource.Properties?.Tags ?? resource.Properties?.tags;
        if (!tags) {
            continue;
        }
        const keys = Array.isArray(tags)
            ? tags.map(tag => (typeof tag.Key === 'string' ? tag.Key : '<intrinsic>'))
            : Object.keys(tags);
        found[resource.Type] = new Set([...(found[resource.Type] ?? []), ...keys]);
    }
    return found;
}

describe.each([
    ['ECS', (app: App, props: typeof defaultProperties) =>
        new SolutionsInfrastructureStack(app, 'TestMigrationAssistantStack', props)],
    ['EKS', (app: App, props: typeof defaultProperties) =>
        new SolutionsInfrastructureEKSStack(app, 'TestMigrationAssistantStack', props)]
])('%s stack tagging', (_name, makeStack) => {
    test.each([true, false])('carries no tags beyond the functional ones (createVPC: %s)', (createVPC) => {
        const template = Template.fromStack(makeStack(new App(), { ...defaultProperties, createVPC }));

        for (const [resourceType, keys] of Object.entries(tagKeysByResourceType(template))) {
            for (const key of keys) {
                // The intrinsic-keyed tag is the EKS cluster tag, which is functional and, being a
                // token, cannot be removed from the TagManager anyway.
                const functional = key === '<intrinsic>'
                    || FUNCTIONAL_TAG_KEY_PREFIXES.some(prefix => key.startsWith(prefix));
                expect(functional).toBe(true);
                // Only subnet discovery needs tags; nothing that costs IAM/ECR/etc. tag permissions.
                expect(resourceType.startsWith('AWS::EC2::')).toBe(true);
            }
        }
    });
});
