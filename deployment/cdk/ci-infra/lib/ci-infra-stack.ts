import { CfnOutput, Duration, RemovalPolicy, Stack, StackProps } from 'aws-cdk-lib';
import {
    AnyPrincipal,
    Effect,
    ManagedPolicy,
    OpenIdConnectProvider,
    PolicyStatement,
} from 'aws-cdk-lib/aws-iam';
import { BlockPublicAccess, Bucket, BucketEncryption, ObjectOwnership } from 'aws-cdk-lib/aws-s3';
import { Construct } from 'constructs';
import { GITHUB_OIDC_HOST, GitHubEnvironmentRole, GitHubRepoSettings } from './github-actions-roles';

// GitHub environments; see .github/workflows/aws-ci-job.yml.
export const MAIN_ENVIRONMENT = 'migrations-ci-main';
export const PR_ENVIRONMENT = 'migrations-ci-pr';

export const TEST_JOB_WORKFLOW = 'aws-ci-job.yml';

// CDK bootstrap qualifiers: the default one deploys this stack (by hand, see README); the tests use their own.
export const INFRA_CDK_QUALIFIER = 'hnb659fds';
export const TEST_CDK_QUALIFIER = 'osmtest';

export const TEST_BOUNDARY_NAME = 'osm-ci-test-boundary';

// Roles the account came with (Isengard, audit, Pipelines), and the customer-managed policies attached to them. None has
// the test boundary, and some have AdministratorAccess, so the tests may not assume, pass or change them. Add new ones
// here if the account gains more.
export const ACCOUNT_BASELINE_ROLES = [
    'Admin',
    'ReadOnly',
    '*-DO-NOT-DELETE',
    'Isengard*',
    '*TrustedService*',
    'Pipelines*',
    'AVM*',
    'awslogs.*',
    'AwsSecurityAudit',
    'CloudSecAuditRole',
    'CloudWatch_Investigations',
    'CodeGuruProfiler*',
    'EC2CapacityReservationService',
    'EpoxyAccessRole',
    'GatedGardenInternalAudit',
    'InternalAuditInternal',
    'OrthancRole',
    'ShadowTrooperRole',
    'TurtleRoleManagement',
];
export const ACCOUNT_BASELINE_POLICIES = ['ConfigAccessPolicy', 'EC2CapacityReservationService'];

// GitHub-hosted jobs stop at 6h.
const TEST_SESSION_DURATION = Duration.hours(6);

export interface CiInfraStackProps extends StackProps {
    readonly github: GitHubRepoSettings;
    /** Managed policies granted to both test roles, under the boundary (cdk.json: AdministratorAccess). */
    readonly testRoleManagedPolicyArns: string[];
}

export class CiInfraStack extends Stack {
    constructor(scope: Construct, id: string, props: CiInfraStackProps) {
        super(scope, id, props);

        // Created once outside this stack (see README), so deleting the stack never deletes it.
        const provider = OpenIdConnectProvider.fromOpenIdConnectProviderArn(
            this,
            'GitHubOidcProvider',
            `arn:aws:iam::${this.account}:oidc-provider/${GITHUB_OIDC_HOST}`,
        );

        const testBoundaryArn = `arn:aws:iam::${this.account}:policy/${TEST_BOUNDARY_NAME}`;
        const testBoundary = new ManagedPolicy(this, 'TestRoleBoundary', {
            managedPolicyName: TEST_BOUNDARY_NAME,
            description: 'Ceiling for the GitHub Actions test roles: no IAM users, no changes to CI identity, audit or account settings',
            statements: [
                new PolicyStatement({
                    sid: 'Ceiling',
                    effect: Effect.ALLOW,
                    actions: ['*'],
                    resources: ['*'],
                }),
                new PolicyStatement({
                    sid: 'NoLongLivedCredentials',
                    effect: Effect.DENY,
                    actions: [
                        'iam:CreateUser',
                        'iam:CreateAccessKey',
                        'iam:CreateLoginProfile',
                        'iam:UpdateLoginProfile',
                        'iam:CreateServiceSpecificCredential',
                    ],
                    resources: ['*'],
                }),
                new PolicyStatement({
                    sid: 'NoChangesToCiIdentity',
                    effect: Effect.DENY,
                    actions: ['iam:*'],
                    resources: [
                        `arn:aws:iam::${this.account}:role/osm-ci-*`,
                        `arn:aws:iam::${this.account}:policy/osm-ci-*`,
                        `arn:aws:iam::${this.account}:oidc-provider/${GITHUB_OIDC_HOST}`,
                        `arn:aws:iam::${this.account}:saml-provider/*`,
                    ],
                }),
                // The default CDK bootstrap deploys this stack, and its execution role has admin rights with no
                // boundary, so the tests may not assume, pass or change its roles, nor change its stack, assets or
                // version parameter. The tests' own bootstrap (osmtest) has this boundary on its execution role.
                new PolicyStatement({
                    sid: 'NoCdkBootstrapRoles',
                    effect: Effect.DENY,
                    actions: ['sts:AssumeRole', 'iam:*'],
                    resources: [`arn:aws:iam::${this.account}:role/cdk-${INFRA_CDK_QUALIFIER}-*`],
                }),
                new PolicyStatement({
                    sid: 'NoChangesToCdkBootstrap',
                    effect: Effect.DENY,
                    actions: ['cloudformation:*'],
                    resources: [`arn:aws:cloudformation:*:${this.account}:stack/CDKToolkit/*`],
                }),
                new PolicyStatement({
                    sid: 'NoChangesToCdkBootstrapAssets',
                    effect: Effect.DENY,
                    actions: [
                        's3:PutObject',
                        's3:DeleteObject',
                        's3:DeleteObjectVersion',
                        's3:PutBucketPolicy',
                        's3:DeleteBucketPolicy',
                        's3:DeleteBucket',
                        's3:PutBucketAcl',
                        's3:PutLifecycleConfiguration',
                        'ecr:*',
                        'ssm:PutParameter',
                        'ssm:DeleteParameter',
                        'ssm:DeleteParameters',
                    ],
                    resources: [
                        `arn:aws:s3:::cdk-${INFRA_CDK_QUALIFIER}-assets-${this.account}-*`,
                        `arn:aws:s3:::cdk-${INFRA_CDK_QUALIFIER}-assets-${this.account}-*/*`,
                        `arn:aws:ecr:*:${this.account}:repository/cdk-${INFRA_CDK_QUALIFIER}-container-assets-*`,
                        `arn:aws:ssm:*:${this.account}:parameter/cdk-bootstrap/${INFRA_CDK_QUALIFIER}/*`,
                    ],
                }),
                new PolicyStatement({
                    sid: 'NoAccountBaselineRoles',
                    effect: Effect.DENY,
                    actions: ['sts:AssumeRole', 'iam:*'],
                    resources: [
                        ...ACCOUNT_BASELINE_ROLES.map((name) => `arn:aws:iam::${this.account}:role/${name}`),
                        ...ACCOUNT_BASELINE_POLICIES.map((name) => `arn:aws:iam::${this.account}:policy/${name}`),
                    ],
                }),
                // Every role a test creates or adds rights to must have this boundary, and no boundary can be
                // removed. The CDK apps set it through @aws-cdk/core:permissionsBoundary (use_test_cdk_settings).
                new PolicyStatement({
                    sid: 'NewRolesCarryThisBoundary',
                    effect: Effect.DENY,
                    actions: ['iam:CreateRole', 'iam:PutRolePermissionsBoundary'],
                    resources: ['*'],
                    conditions: { StringNotEquals: { 'iam:PermissionsBoundary': testBoundaryArn } },
                }),
                new PolicyStatement({
                    sid: 'NoRightsForRolesWithoutThisBoundary',
                    effect: Effect.DENY,
                    actions: ['iam:AttachRolePolicy', 'iam:PutRolePolicy'],
                    resources: ['*'],
                    conditions: { StringNotEquals: { 'iam:PermissionsBoundary': testBoundaryArn } },
                }),
                new PolicyStatement({
                    sid: 'NoBoundaryRemoval',
                    effect: Effect.DENY,
                    actions: ['iam:DeleteRolePermissionsBoundary'],
                    resources: ['*'],
                }),
                // The tests assume and pass the osmtest bootstrap roles but may not change them. Only an admin
                // re-runs `cdk bootstrap` (README, setup step 4).
                new PolicyStatement({
                    sid: 'NoChangesToTestCdkBootstrapRoles',
                    effect: Effect.DENY,
                    actions: [
                        'iam:DeleteRolePermissionsBoundary',
                        'iam:PutRolePermissionsBoundary',
                        'iam:UpdateAssumeRolePolicy',
                        'iam:PutRolePolicy',
                        'iam:DeleteRolePolicy',
                        'iam:AttachRolePolicy',
                        'iam:DetachRolePolicy',
                        'iam:DeleteRole',
                    ],
                    resources: [`arn:aws:iam::${this.account}:role/cdk-${TEST_CDK_QUALIFIER}-*`],
                }),
                // S3 Block Public Access is on for the whole account; tests may not turn it off, nor share snapshots
                // or images with other accounts.
                new PolicyStatement({
                    sid: 'NoPublicOrSharedData',
                    effect: Effect.DENY,
                    actions: [
                        's3:PutAccountPublicAccessBlock',
                        'ec2:ModifySnapshotAttribute',
                        'ec2:ModifyImageAttribute',
                        'rds:ModifyDBSnapshotAttribute',
                        'rds:ModifyDBClusterSnapshotAttribute',
                    ],
                    resources: ['*'],
                }),
                new PolicyStatement({
                    sid: 'NoChangesToThisStack',
                    effect: Effect.DENY,
                    actions: ['cloudformation:*'],
                    resources: [`arn:aws:cloudformation:*:${this.account}:stack/${this.stackName}/*`],
                }),
                new PolicyStatement({
                    sid: 'NoAuditOrAccountChanges',
                    effect: Effect.DENY,
                    actions: [
                        'cloudtrail:StopLogging',
                        'cloudtrail:DeleteTrail',
                        'cloudtrail:UpdateTrail',
                        'cloudtrail:PutEventSelectors',
                        'organizations:*',
                        'account:*',
                    ],
                    resources: ['*'],
                }),
            ],
        });

        const testPolicies = props.testRoleManagedPolicyArns.map((arn, i) =>
            ManagedPolicy.fromManagedPolicyArn(this, `TestRolePolicy${i}`, arn));

        const mainRole = new GitHubEnvironmentRole(this, 'MainTestRole', provider, {
            roleName: 'osm-ci-main',
            description: `AWS integration tests from ${props.github.repository} main, scheduled and release-canary runs`,
            github: props.github,
            environment: MAIN_ENVIRONMENT,
            workflowFile: TEST_JOB_WORKFLOW,
            maxSessionDuration: TEST_SESSION_DURATION,
            permissionsBoundary: testBoundary,
        });

        const prRole = new GitHubEnvironmentRole(this, 'PrTestRole', provider, {
            roleName: 'osm-ci-pr',
            description: `AWS integration tests for ${props.github.repository} PRs, after a maintainer label and approval`,
            github: props.github,
            environment: PR_ENVIRONMENT,
            workflowFile: TEST_JOB_WORKFLOW,
            maxSessionDuration: TEST_SESSION_DURATION,
            permissionsBoundary: testBoundary,
        });

        for (const policy of testPolicies) {
            mainRole.addManagedPolicy(policy);
            prRole.addManagedPolicy(policy);
        }

        // Snapshots the AOSS and BYOS tests migrate. The name starts with "migrations-" so the Migration Assistant pod
        // roles can read it. Only the account's Admin role may write or delete.
        const snapshotLibrary = new Bucket(this, 'SnapshotLibrary', {
            bucketName: `migrations-snapshots-library-${this.account}-${this.region}`,
            blockPublicAccess: BlockPublicAccess.BLOCK_ALL,
            encryption: BucketEncryption.S3_MANAGED,
            objectOwnership: ObjectOwnership.BUCKET_OWNER_ENFORCED,
            enforceSSL: true,
            removalPolicy: RemovalPolicy.RETAIN,
        });
        // The infra CDK execution role updates this bucket's policy on deploy.
        const writers = [
            `arn:aws:iam::${this.account}:role/Admin`,
            `arn:aws:iam::${this.account}:role/cdk-${INFRA_CDK_QUALIFIER}-cfn-exec-role-${this.account}-${this.region}`,
        ];
        snapshotLibrary.addToResourcePolicy(new PolicyStatement({
            sid: 'OnlyAdminsWrite',
            effect: Effect.DENY,
            principals: [new AnyPrincipal()],
            actions: ['s3:PutObject', 's3:DeleteObject', 's3:DeleteObjectVersion', 's3:PutBucketPolicy',
                's3:DeleteBucketPolicy', 's3:DeleteBucket', 's3:PutLifecycleConfiguration'],
            resources: [snapshotLibrary.bucketArn, snapshotLibrary.arnForObjects('*')],
            conditions: { ArnNotLike: { 'aws:PrincipalArn': writers } },
        }));

        new CfnOutput(this, 'SnapshotLibraryBucket', { value: snapshotLibrary.bucketName });
        new CfnOutput(this, 'MainTestRoleArn', { value: mainRole.roleArn, description: 'Secret MIGRATIONS_CI_MAIN_ROLE' });
        new CfnOutput(this, 'PrTestRoleArn', { value: prRole.roleArn, description: 'Secret MIGRATIONS_CI_PR_ROLE' });
    }
}
