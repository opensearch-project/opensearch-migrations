import { Duration } from 'aws-cdk-lib';
import {
    IManagedPolicy,
    IOpenIdConnectProvider,
    OpenIdConnectPrincipal,
    Role,
} from 'aws-cdk-lib/aws-iam';
import { Construct } from 'constructs';

export const GITHUB_OIDC_HOST = 'token.actions.githubusercontent.com';

export interface GitHubRepoSettings {
    /** owner/repo, e.g. opensearch-project/opensearch-migrations */
    readonly repository: string;
    /**
     * Prefix of the token's `sub` claim. Classic repos use `repo:<owner>/<repo>`; repos on the immutable format use
     * `repo:<owner>@<id>/<repo>@<id>`. Read it from `gh api repos/<owner>/<repo>/actions/oidc/customization/sub`.
     */
    readonly subjectPrefix: string;
    /** Git ref the trusted workflow files are read from (`refs/heads/main` upstream). */
    readonly workflowRef: string;
}

export interface GitHubEnvironmentRoleProps {
    readonly roleName: string;
    readonly description: string;
    readonly github: GitHubRepoSettings;
    /** GitHub environment the job must run in. */
    readonly environment: string;
    /** Workflow file (under .github/workflows/) whose jobs may assume the role. */
    readonly workflowFile: string;
    readonly maxSessionDuration: Duration;
    readonly permissionsBoundary?: IManagedPolicy;
}

/**
 * Trust conditions for a role that GitHub Actions jobs assume through OIDC.
 *
 * Roles are bound to a GitHub environment, not a branch: for `pull_request_target` the token's `sub` is the base ref
 * (`ref:refs/heads/main`), the same as a push to main, so a ref-based policy can't tell PR runs from main runs. A job
 * that names an environment gets `sub` = `<prefix>:environment:<name>` instead.
 */
export function environmentTrustConditions(
    github: GitHubRepoSettings,
    environment: string,
    workflowFile: string,
): Record<string, Record<string, string>> {
    // job_workflow_ref pins the workflow file and the ref it's read from.
    return {
        StringEquals: {
            [`${GITHUB_OIDC_HOST}:aud`]: 'sts.amazonaws.com',
            [`${GITHUB_OIDC_HOST}:sub`]: `${github.subjectPrefix}:environment:${environment}`,
            [`${GITHUB_OIDC_HOST}:job_workflow_ref`]:
                `${github.repository}/.github/workflows/${workflowFile}@${github.workflowRef}`,
        },
    };
}

/** An IAM role that only jobs running in one GitHub environment, from one workflow file, can assume. */
export class GitHubEnvironmentRole extends Role {
    constructor(scope: Construct, id: string, provider: IOpenIdConnectProvider, props: GitHubEnvironmentRoleProps) {
        super(scope, id, {
            roleName: props.roleName,
            description: props.description,
            assumedBy: new OpenIdConnectPrincipal(
                provider,
                environmentTrustConditions(props.github, props.environment, props.workflowFile),
            ),
            maxSessionDuration: props.maxSessionDuration,
            permissionsBoundary: props.permissionsBoundary,
        });
    }
}
