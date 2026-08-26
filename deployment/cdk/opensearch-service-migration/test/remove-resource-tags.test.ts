import { Aspects, Stack } from "aws-cdk-lib";
import { Template } from "aws-cdk-lib/assertions";
import { describe, expect, test } from '@jest/globals';
import { FUNCTIONAL_TAG_KEY_PREFIXES, RemoveResourceTags } from "../lib/remove-resource-tags";
import { createStackComposer } from "./test-utils";

/**
 * Collects the tag keys rendered into a template, keyed by resource type. Tag keys that are
 * CloudFormation intrinsics rather than literals are reported as `<intrinsic>`.
 */
function tagKeysByResourceType(stack: Stack): Record<string, Set<string>> {
    const found: Record<string, Set<string>> = {};
    const resources = Template.fromStack(stack).toJSON().Resources ?? {};
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

describe('RemoveResourceTags', () => {
    const contextOptions = {
        vpcEnabled: true,
        migrationAssistanceEnabled: true,
        migrationConsoleServiceEnabled: true,
        sourceClusterEndpoint: "https://test-cluster",
        reindexFromSnapshotServiceEnabled: true,
        trafficReplayerServiceEnabled: true,
        nodeToNodeEncryptionEnabled: true,
        encryptionAtRestEnabled: true,
        enforceHTTPS: true
    };

    test('leaves only the functional tags across every stack', () => {
        const composer = createStackComposer({ ...contextOptions });
        Aspects.of(composer.stacks[0].node.root).add(new RemoveResourceTags(),
            { priority: RemoveResourceTags.PRIORITY });

        let taggedResourceTypes = 0;
        for (const stack of composer.stacks) {
            for (const [resourceType, keys] of Object.entries(tagKeysByResourceType(stack))) {
                taggedResourceTypes++;
                // Only subnet discovery needs tags; nothing that costs IAM/ECR/etc. tag permissions.
                expect(resourceType).toMatch(/^AWS::EC2::/);
                for (const key of keys) {
                    expect(FUNCTIONAL_TAG_KEY_PREFIXES.some(prefix => key.startsWith(prefix))).toBe(true);
                }
            }
        }
        // Guards against the assertions above passing because nothing was rendered at all.
        expect(taggedResourceTypes).toBeGreaterThan(0);
    });

    test('does not remove tags it is configured to keep', () => {
        const composer = createStackComposer({ ...contextOptions });
        Aspects.of(composer.stacks[0].node.root).add(new RemoveResourceTags(['aws-cdk:']),
            { priority: RemoveResourceTags.PRIORITY });

        const subnetTags = Object.entries(tagKeysByResourceType(composer.stacks[0]))
            .filter(([resourceType]) => resourceType === 'AWS::EC2::Subnet')
            .flatMap(([, keys]) => [...keys]);
        expect(subnetTags).toEqual(expect.arrayContaining(['aws-cdk:subnet-name', 'aws-cdk:subnet-type']));
        expect(subnetTags).not.toContain('Name');
    });
});
