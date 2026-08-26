import { CfnResource, IAspect, TagManager } from 'aws-cdk-lib';
import { IConstruct } from 'constructs';

/**
 * Tag key prefixes that must survive because something reads them back:
 *   - `aws-cdk:` — `aws-cdk:subnet-name` / `aws-cdk:subnet-type`, used by `Vpc.fromLookup()`
 *     to classify existing subnets as public/private/isolated.
 *   - `kubernetes.io/` — `kubernetes.io/cluster/<name>`, `kubernetes.io/role/elb` and
 *     `kubernetes.io/role/internal-elb`, used by the AWS Load Balancer Controller to discover
 *     the subnets it may place load balancers in.
 *
 * Everything else is descriptive only, and tagging it costs the deployer `<service>:TagResource`
 * and `<service>:UntagResource` permissions on every tagged resource type.
 */
export const FUNCTIONAL_TAG_KEY_PREFIXES = ['aws-cdk:', 'kubernetes.io/'];

/**
 * Removes every non-functional tag from every taggable resource in the tree, so that deployments
 * do not require tag/untag permissions. Some organizations deny those actions via SCPs, and even
 * where tagging is permitted, changing a tag's value (for example a version-valued tag) is an
 * untag plus a tag, so the permissions are needed on every update as well as on create.
 */
export class RemoveResourceTags implements IAspect {
    /**
     * Aspect priority. This must be higher than `AspectPriority.MUTATING` (200) so that this
     * aspect visits each node *after* the `Tag` aspects that `Tags.of(...).add(...)` registers,
     * including the node-local ones that CDK's own L2 constructs add to themselves. Without an
     * explicit priority, aspects inherited from an ancestor run before a node's own aspects and
     * the tags added by those would survive.
     */
    static readonly PRIORITY = 1000;

    constructor(private readonly keepPrefixes: string[] = FUNCTIONAL_TAG_KEY_PREFIXES) {}

    visit(node: IConstruct): void {
        if (!CfnResource.isCfnResource(node)) {
            return;
        }
        // `TagManager.of` covers both L1 tagging conventions: the `tags` property on older
        // generated resources and the `cdkTagManager` property on newer ones.
        const tagManager = TagManager.of(node);
        if (!tagManager) {
            return;
        }
        for (const key of Object.keys(tagManager.tagValues())) {
            if (!this.keepPrefixes.some(prefix => key.startsWith(prefix))) {
                // A deletion override on `Tags` does not work here: `CfnResource._toCloudFormation`
                // resolves the raw overrides before merging them, which drops the `undefined` that
                // signals the deletion. The `TagManager` has to be mutated instead.
                tagManager.removeTag(key, RemoveResourceTags.PRIORITY);
            }
        }
    }
}
