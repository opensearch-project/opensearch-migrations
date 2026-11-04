import { App } from 'aws-cdk-lib';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { describe, expect, test } from '@jest/globals';
import { CiInfraStack } from '../lib/ci-infra-stack';
import { GitHubRepoSettings } from '../lib/github-actions-roles';

const UPSTREAM: GitHubRepoSettings = {
    repository: 'opensearch-project/opensearch-migrations',
    subjectPrefix: 'repo:opensearch-project/opensearch-migrations',
    workflowRef: 'refs/heads/main',
};

function synth(github: GitHubRepoSettings = UPSTREAM, testRoleManagedPolicyArns = ['arn:aws:iam::aws:policy/AdministratorAccess']) {
    const app = new App();
    const stack = new CiInfraStack(app, 'osm-ci-infra', {
        env: { account: '111122223333', region: 'us-east-1' },
        github,
        testRoleManagedPolicyArns,
    });
    return Template.fromStack(stack);
}

function trustConditions(template: Template, roleName: string) {
    const roles = template.findResources('AWS::IAM::Role', { Properties: { RoleName: roleName } });
    const [role] = Object.values(roles);
    return role.Properties.AssumeRolePolicyDocument.Statement[0];
}

describe('CiInfraStack', () => {
    test('does not create the OIDC provider (it is created once outside the stack)', () => {
        const template = synth();
        template.resourceCountIs('AWS::IAM::OIDCProvider', 0);
        template.resourceCountIs('Custom::AWSCDKOpenIdConnectProvider', 0);
    });

    test.each([
        ['osm-ci-main', 'migrations-ci-main', 'aws-ci-job.yml'],
        ['osm-ci-pr', 'migrations-ci-pr', 'aws-ci-job.yml'],
    ])('%s trusts only the %s environment and %s on main', (roleName, environment, workflowFile) => {
        const statement = trustConditions(synth(), roleName);
        expect(statement.Action).toBe('sts:AssumeRoleWithWebIdentity');
        expect(statement.Principal.Federated).toBe(
            'arn:aws:iam::111122223333:oidc-provider/token.actions.githubusercontent.com');
        expect(statement.Condition).toEqual({
            StringEquals: {
                'token.actions.githubusercontent.com:aud': 'sts.amazonaws.com',
                'token.actions.githubusercontent.com:sub':
                    `repo:opensearch-project/opensearch-migrations:environment:${environment}`,
                'token.actions.githubusercontent.com:job_workflow_ref':
                    `opensearch-project/opensearch-migrations/.github/workflows/${workflowFile}@refs/heads/main`,
            },
        });
    });

    test('no role trusts a branch or tag subject, which pull_request_target tokens share with main', () => {
        const roles = Object.values(synth().findResources('AWS::IAM::Role'));
        expect(roles).toHaveLength(2);
        for (const role of roles) {
            const sub = role.Properties.AssumeRolePolicyDocument.Statement[0].Condition.StringEquals[
                'token.actions.githubusercontent.com:sub'];
            expect(sub).toMatch(/:environment:/);
        }
    });

    test('supports a fork on the immutable subject format and a test branch', () => {
        const statement = trustConditions(synth({
            repository: 'example-user/opensearch-migrations',
            subjectPrefix: 'repo:example-user@100/opensearch-migrations@200',
            workflowRef: 'refs/heads/my-test-branch',
        }), 'osm-ci-pr');
        expect(statement.Condition).toEqual({
            StringEquals: {
                'token.actions.githubusercontent.com:aud': 'sts.amazonaws.com',
                'token.actions.githubusercontent.com:sub':
                    'repo:example-user@100/opensearch-migrations@200:environment:migrations-ci-pr',
                'token.actions.githubusercontent.com:job_workflow_ref':
                    'example-user/opensearch-migrations/.github/workflows/aws-ci-job.yml@refs/heads/my-test-branch',
            },
        });
    });

    test('test roles run up to 6h under the boundary with the configured policies', () => {
        const template = synth();
        for (const roleName of ['osm-ci-main', 'osm-ci-pr']) {
            template.hasResourceProperties('AWS::IAM::Role', {
                RoleName: roleName,
                MaxSessionDuration: 21600,
                PermissionsBoundary: { Ref: Match.stringLikeRegexp('TestRoleBoundary') },
                ManagedPolicyArns: ['arn:aws:iam::aws:policy/AdministratorAccess'],
            });
        }
    });

    test('boundary denies the escalation paths', () => {
        const template = synth();
        const [boundary] = Object.values(template.findResources('AWS::IAM::ManagedPolicy', {
            Properties: { ManagedPolicyName: 'osm-ci-test-boundary' },
        }));
        const denies = boundary.Properties.PolicyDocument.Statement
            .filter((s: { Effect: string }) => s.Effect === 'Deny')
            .map((s: { Sid: string }) => s.Sid);
        expect(denies).toEqual([
            'NoLongLivedCredentials',
            'NoChangesToCiIdentity',
            'NoCdkBootstrapRoles',
            'NoChangesToCdkBootstrap',
            'NoChangesToCdkBootstrapAssets',
            'NoAccountBaselineRoles',
            'NewRolesCarryThisBoundary',
            'NoRightsForRolesWithoutThisBoundary',
            'NoBoundaryRemoval',
            'NoChangesToTestCdkBootstrapRoles',
            'NoPublicOrSharedData',
            'NoChangesToThisStack',
            'NoAuditOrAccountChanges',
        ]);
    });

    test('boundary blocks only the infra CDK bootstrap roles, leaving the osmtest bootstrap to tests', () => {
        const [boundary] = Object.values(synth().findResources('AWS::IAM::ManagedPolicy', {
            Properties: { ManagedPolicyName: 'osm-ci-test-boundary' },
        }));
        const statements = boundary.Properties.PolicyDocument.Statement;
        const sid = (name: string) => statements.find((s: { Sid: string }) => s.Sid === name);

        // Not assumed, passed to a service (CloudFormation --role-arn) or changed.
        expect(sid('NoCdkBootstrapRoles')).toMatchObject({
            Effect: 'Deny',
            Action: ['sts:AssumeRole', 'iam:*'],
            Resource: 'arn:aws:iam::111122223333:role/cdk-hnb659fds-*',
        });
        expect(sid('NoChangesToCdkBootstrap')).toMatchObject({
            Effect: 'Deny',
            Action: 'cloudformation:*',
            Resource: 'arn:aws:cloudformation:*:111122223333:stack/CDKToolkit/*',
        });
        const assets = sid('NoChangesToCdkBootstrapAssets');
        expect(assets.Effect).toBe('Deny');
        expect(assets.Action).toEqual(expect.arrayContaining(['s3:PutObject', 'ecr:*', 'ssm:PutParameter']));
        expect(assets.Resource).toEqual([
            'arn:aws:s3:::cdk-hnb659fds-assets-111122223333-*',
            'arn:aws:s3:::cdk-hnb659fds-assets-111122223333-*/*',
            'arn:aws:ecr:*:111122223333:repository/cdk-hnb659fds-container-assets-*',
            'arn:aws:ssm:*:111122223333:parameter/cdk-bootstrap/hnb659fds/*',
        ]);

        // Nothing in these denies names the tests' osmtest bootstrap (or its CDKToolkitTests stack).
        const denied = JSON.stringify([sid('NoCdkBootstrapRoles'), sid('NoChangesToCdkBootstrap'), assets]);
        expect(denied).not.toMatch(/osmtest|CDKToolkitTests/);
    });

    test('boundary lets tests assume and pass the osmtest bootstrap roles but not change them', () => {
        const [boundary] = Object.values(synth().findResources('AWS::IAM::ManagedPolicy', {
            Properties: { ManagedPolicyName: 'osm-ci-test-boundary' },
        }));
        const statement = boundary.Properties.PolicyDocument.Statement
            .find((s: { Sid: string }) => s.Sid === 'NoChangesToTestCdkBootstrapRoles');
        expect(statement.Effect).toBe('Deny');
        expect(statement.Resource).toBe('arn:aws:iam::111122223333:role/cdk-osmtest-*');
        expect(statement.Action).toEqual(expect.arrayContaining([
            'iam:DeleteRolePermissionsBoundary', 'iam:UpdateAssumeRolePolicy', 'iam:PutRolePolicy', 'iam:AttachRolePolicy',
        ]));
        expect(statement.Action).not.toContain('iam:PassRole');
        expect(statement.Action).not.toContain('sts:AssumeRole');
    });

    test('boundary keeps the tests away from the roles the account came with', () => {
        const [boundary] = Object.values(synth().findResources('AWS::IAM::ManagedPolicy', {
            Properties: { ManagedPolicyName: 'osm-ci-test-boundary' },
        }));
        const statement = boundary.Properties.PolicyDocument.Statement
            .find((s: { Sid: string }) => s.Sid === 'NoAccountBaselineRoles');
        expect(statement).toMatchObject({ Effect: 'Deny', Action: ['sts:AssumeRole', 'iam:*'] });
        expect(statement.Resource).toEqual(expect.arrayContaining([
            'arn:aws:iam::111122223333:role/Admin',
            'arn:aws:iam::111122223333:role/Pipelines*',
            'arn:aws:iam::111122223333:role/Isengard*',
            'arn:aws:iam::111122223333:policy/ConfigAccessPolicy',
        ]));
        // The test bootstrap and the test roles themselves stay out of it.
        expect(JSON.stringify(statement.Resource)).not.toMatch(/osmtest|osm-ci|role\/\*"/);
    });

    test('boundary makes every role a test creates or extends carry the boundary', () => {
        const [boundary] = Object.values(synth().findResources('AWS::IAM::ManagedPolicy', {
            Properties: { ManagedPolicyName: 'osm-ci-test-boundary' },
        }));
        const statements = boundary.Properties.PolicyDocument.Statement;
        const sid = (name: string) => statements.find((s: { Sid: string }) => s.Sid === name);
        const onlyWithBoundary = {
            StringNotEquals: { 'iam:PermissionsBoundary': 'arn:aws:iam::111122223333:policy/osm-ci-test-boundary' },
        };
        expect(sid('NewRolesCarryThisBoundary')).toEqual({
            Sid: 'NewRolesCarryThisBoundary', Effect: 'Deny', Resource: '*',
            Action: ['iam:CreateRole', 'iam:PutRolePermissionsBoundary'], Condition: onlyWithBoundary,
        });
        expect(sid('NoRightsForRolesWithoutThisBoundary')).toEqual({
            Sid: 'NoRightsForRolesWithoutThisBoundary', Effect: 'Deny', Resource: '*',
            Action: ['iam:AttachRolePolicy', 'iam:PutRolePolicy'], Condition: onlyWithBoundary,
        });
        expect(sid('NoBoundaryRemoval')).toEqual({
            Sid: 'NoBoundaryRemoval', Effect: 'Deny', Resource: '*', Action: 'iam:DeleteRolePermissionsBoundary',
        });
    });

    test('boundary fits the managed policy size limit', () => {
        const [boundary] = Object.values(synth().findResources('AWS::IAM::ManagedPolicy', {
            Properties: { ManagedPolicyName: 'osm-ci-test-boundary' },
        }));
        const size = JSON.stringify(boundary.Properties.PolicyDocument).replace(/\s/g, '').length;
        expect(size).toBeLessThan(6144);
    });

    test('boundary keeps S3 Block Public Access on and data unshared', () => {
        const [boundary] = Object.values(synth().findResources('AWS::IAM::ManagedPolicy', {
            Properties: { ManagedPolicyName: 'osm-ci-test-boundary' },
        }));
        const statement = boundary.Properties.PolicyDocument.Statement
            .find((s: { Sid: string }) => s.Sid === 'NoPublicOrSharedData');
        expect(statement).toMatchObject({ Effect: 'Deny', Resource: '*' });
        expect(statement.Action).toEqual(expect.arrayContaining([
            's3:PutAccountPublicAccessBlock', 'ec2:ModifySnapshotAttribute', 'rds:ModifyDBSnapshotAttribute',
        ]));
    });

    test('no role can deploy this stack: it is deployed by hand (see README)', () => {
        const template = synth();
        expect(Object.keys(template.findResources('AWS::IAM::Policy'))).toHaveLength(0);
        expect(Object.keys(template.findOutputs('*'))).toEqual(['SnapshotLibraryBucket', 'MainTestRoleArn', 'PrTestRoleArn']);
    });

    test('snapshot library is retained, private, readable by MA pods and writable only by admins', () => {
        const template = synth();
        template.hasResource('AWS::S3::Bucket', {
            DeletionPolicy: 'Retain',
            Properties: {
                BucketName: 'migrations-snapshots-library-111122223333-us-east-1',
                PublicAccessBlockConfiguration: {
                    BlockPublicAcls: true, BlockPublicPolicy: true, IgnorePublicAcls: true, RestrictPublicBuckets: true,
                },
                OwnershipControls: { Rules: [{ ObjectOwnership: 'BucketOwnerEnforced' }] },
            },
        });
        const [policy] = Object.values(template.findResources('AWS::S3::BucketPolicy'));
        const deny = policy.Properties.PolicyDocument.Statement.find((s: { Sid?: string }) => s.Sid === 'OnlyAdminsWrite');
        expect(deny.Effect).toBe('Deny');
        expect(deny.Condition).toEqual({ ArnNotLike: { 'aws:PrincipalArn': [
            'arn:aws:iam::111122223333:role/Admin',
            'arn:aws:iam::111122223333:role/cdk-hnb659fds-cfn-exec-role-111122223333-us-east-1',
        ] } });
    });
});
