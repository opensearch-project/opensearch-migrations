import {Construct} from 'constructs';
import {CfnPodIdentityAssociation} from 'aws-cdk-lib/aws-eks';
import * as eks from 'aws-cdk-lib/aws-eks-v2';
import {IVpc, Subnet} from 'aws-cdk-lib/aws-ec2';
import {
    Effect,
    Policy,
    PolicyStatement,
    Role,
    ServicePrincipal,
} from "aws-cdk-lib/aws-iam";
import {Aws, RemovalPolicy, Stack, Tags} from "aws-cdk-lib";
import {Repository} from "aws-cdk-lib/aws-ecr";


export interface EKSInfraProps {
    vpc: IVpc;
    clusterName: string;
    ecrRepoName: string;
    stageName: string;
    vpcSubnetIds?: string[];
    namespace?: string;
    buildImagesServiceAccountName?: string;
    argoWorkflowServiceAccountName?: string;
    migrationsServiceAccountName?: string;
    migrationConsoleServiceAccountName?: string;
    otelCollectorServiceAccountName?: string;
    argoTestWorkflowServiceAccountName?: string;
    enablePCA?: boolean;
    enableACKPCA?: boolean;
    enableACKCloudWatch?: boolean;
}

export class EKSInfra extends Construct {
    public readonly cluster: eks.Cluster;
    public readonly ecrRepo: Repository;
    public readonly snapshotRole: Role;

    constructor(scope: Construct, id: string, props: EKSInfraProps) {
        super(scope, id);

        const namespace = props.namespace ?? 'ma';
        const buildImagesServiceAccountName = props.buildImagesServiceAccountName ?? 'build-images-service-account';
        const argoWorkflowServiceAccountName = props.argoWorkflowServiceAccountName ?? 'argo-workflow-executor';
        const migrationsServiceAccountName = props.migrationsServiceAccountName ?? 'migrations-service-account';
        const migrationConsoleServiceAccountName = props.migrationConsoleServiceAccountName ?? 'migration-console-access-role';
        const otelCollectorServiceAccountName = props.otelCollectorServiceAccountName ?? 'otel-collector';
        const argoTestWorkflowServiceAccountName = props.argoTestWorkflowServiceAccountName ?? 'argo-test-workflow-executor';

        this.ecrRepo = new Repository(this, 'MigrationsECRRepository', {
            repositoryName: props.ecrRepoName,
            removalPolicy: RemovalPolicy.DESTROY,
            emptyOnDelete: true
        });

        let vpcSubnets;
        if (props.vpcSubnetIds && props.vpcSubnetIds.length > 0) {
            const importedSubnets = props.vpcSubnetIds.map((subnetId, i) =>
                Subnet.fromSubnetId(this, `ImportedSubnet${i}`, subnetId)
            );
            vpcSubnets = [{ subnets: importedSubnets }];
        } else {
            for (const subnet of props.vpc.privateSubnets) {
                Tags.of(subnet).add(`kubernetes.io/cluster/${props.clusterName}`, 'shared');
                Tags.of(subnet).add('kubernetes.io/role/internal-elb', '1');
            }
            vpcSubnets = [{ subnets: props.vpc.privateSubnets }];
        }

        this.cluster = new eks.Cluster(this, 'MigrationsEKSCluster', {
            clusterName: props.clusterName,
            version: eks.KubernetesVersion.of('1.35'),
            vpc: props.vpc,
            vpcSubnets,
        });

        // Grant EKS readonly access to all IAM principals in the account
        this.cluster.grantAccess('AccountReadonlyAccess',
            `arn:${Aws.PARTITION}:iam::${Stack.of(this).account}:root`,
            [eks.AccessPolicy.fromAccessPolicyName('AmazonEKSViewPolicy', {
                accessScopeType: eks.AccessScopeType.CLUSTER,
            })],
        );

        const stack = Stack.of(this);
        const migrationsBucketArn =
            `arn:${Aws.PARTITION}:s3:::migrations-default-${stack.account}-${props.stageName}-${stack.region}`;
        const podIdentityRole = this.createDefaultPodIdentityRole(
            props.clusterName,
            props.stageName,
            migrationsBucketArn
        )
        this.snapshotRole = new Role(scope, `SnapshotRole`, {
            assumedBy: new ServicePrincipal('es.amazonaws.com'),  // Note that snapshots are not currently possible on AOSS
            description: 'Role that grants OpenSearch Service permissions to access S3 to create snapshots',
            roleName: `${props.clusterName}-snapshot-role`
        });
        this.snapshotRole.addToPolicy(new PolicyStatement({
            effect: Effect.ALLOW,
            actions: ['s3:ListBucket'],
            resources: [migrationsBucketArn],
        }));
        this.snapshotRole.addToPolicy(new PolicyStatement({
            effect: Effect.ALLOW,
            actions: ['s3:GetObject', 's3:PutObject', 's3:DeleteObject'],
            resources: [`${migrationsBucketArn}/*`],
        }));
        this.snapshotRole.grantPassRole(podIdentityRole);

        const buildImagesPodIdentityAssociation = new CfnPodIdentityAssociation(this, 'BuildImagesPodIdentityAssociation', {
            clusterName: props.clusterName,
            namespace: namespace,
            serviceAccount: buildImagesServiceAccountName,
            roleArn: podIdentityRole.roleArn,
        });
        const argoWorkflowIdentityAssociation = new CfnPodIdentityAssociation(this, 'ArgoWorkflowPodIdentityAssociation', {
            clusterName: props.clusterName,
            namespace: namespace,
            serviceAccount: argoWorkflowServiceAccountName,
            roleArn: podIdentityRole.roleArn,
        });
        const migrationsPodIdentityAssociation = new CfnPodIdentityAssociation(this, 'MigrationsPodIdentityAssociation', {
            clusterName: props.clusterName,
            namespace: namespace,
            serviceAccount: migrationsServiceAccountName,
            roleArn: podIdentityRole.roleArn,
        });
        const migrationConsolePodIdentityAssociation = new CfnPodIdentityAssociation(this, 'MigrationConsolePodIdentityAssociation', {
            clusterName: props.clusterName,
            namespace: namespace,
            serviceAccount: migrationConsoleServiceAccountName,
            roleArn: podIdentityRole.roleArn,
        });
        const otelCollectorPodIdentityAssociation = new CfnPodIdentityAssociation(this, 'OtelCollectorPodIdentityAssociation', {
            clusterName: props.clusterName,
            namespace: namespace,
            serviceAccount: otelCollectorServiceAccountName,
            roleArn: podIdentityRole.roleArn,
        });
        const argoTestWorkflowIdentityAssociation = new CfnPodIdentityAssociation(this, 'ArgoTestWorkflowPodIdentityAssociation', {
            clusterName: props.clusterName,
            namespace: namespace,
            serviceAccount: argoTestWorkflowServiceAccountName,
            roleArn: podIdentityRole.roleArn,
        });
        buildImagesPodIdentityAssociation.node.addDependency(this.cluster)
        argoWorkflowIdentityAssociation.node.addDependency(this.cluster)
        migrationsPodIdentityAssociation.node.addDependency(this.cluster)
        migrationConsolePodIdentityAssociation.node.addDependency(this.cluster)
        otelCollectorPodIdentityAssociation.node.addDependency(this.cluster)
        argoTestWorkflowIdentityAssociation.node.addDependency(this.cluster)

        // PCA Pod Identity Associations — conditional on enablePCA/enableACKPCA
        if (props.enablePCA) {
            const pcaIssuerPodIdentityAssociation = new CfnPodIdentityAssociation(this, 'PcaIssuerPodIdentityAssociation', {
                clusterName: props.clusterName,
                namespace: namespace,
                serviceAccount: 'aws-pca-issuer',
                roleArn: podIdentityRole.roleArn,
            });
            pcaIssuerPodIdentityAssociation.node.addDependency(this.cluster)
        }
        if (props.enableACKPCA) {
            const ackAcmpcaPodIdentityAssociation = new CfnPodIdentityAssociation(this, 'AckAcmpcaPodIdentityAssociation', {
                clusterName: props.clusterName,
                namespace: namespace,
                serviceAccount: 'ack-acmpca-controller',
                roleArn: podIdentityRole.roleArn,
            });
            ackAcmpcaPodIdentityAssociation.node.addDependency(this.cluster)
        }
        if (props.enableACKCloudWatch) {
            const ackCloudWatchPodIdentityAssociation = new CfnPodIdentityAssociation(this, 'AckCloudWatchPodIdentityAssociation', {
                clusterName: props.clusterName,
                namespace: namespace,
                serviceAccount: 'ack-cloudwatch-controller',
                roleArn: podIdentityRole.roleArn,
            });
            ackCloudWatchPodIdentityAssociation.node.addDependency(this.cluster)
        }
    }

    createDefaultPodIdentityRole(clusterName: string, stageName: string, migrationsBucketArn: string) {
        const stack = Stack.of(this);
        const account = stack.account;
        const region = stack.region;
        const migrationLogGroupArn =
            `arn:${Aws.PARTITION}:logs:${region}:${account}:log-group:/migration-assistant-${stageName}-${region}/logs`;

        const podIdentityRole = new Role(this, 'MigrationsPodIdentityRole', {
            roleName: `${clusterName}-migrations-role`,
            description: 'Migrations IAM role assumed by pods via EKS Pod Identity',
            assumedBy: new ServicePrincipal('pods.eks.amazonaws.com'),
        });
        podIdentityRole.assumeRolePolicy?.addStatements(
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: ['sts:AssumeRole', 'sts:TagSession'],
                principals: [new ServicePrincipal('pods.eks.amazonaws.com')]
            })
        );
        const podIdentityPolicy = new Policy(this, 'MigrationsPodIdentityPolicy', {
            policyName: 'MigrationsPodPolicy',
            roles: [podIdentityRole],
        });
        podIdentityPolicy.addStatements(
            // ECR authorization tokens do not support resource-level permissions.
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: ['ecr:GetAuthorizationToken'],
                resources: ['*'],
                conditions: {
                    StringEquals: {
                        'aws:RequestedRegion': region,
                    },
                },
            }),
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: [
                    'ecr:BatchGetImage',
                    'ecr:GetDownloadUrlForLayer',
                    'ecr:BatchCheckLayerAvailability',
                    'ecr:CompleteLayerUpload',
                    'ecr:InitiateLayerUpload',
                    'ecr:PutImage',
                    'ecr:UploadLayerPart',
                ],
                resources: [this.ecrRepo.repositoryArn],
            }),
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: ['es:ESHttp*'],
                resources: [
                    `arn:${Aws.PARTITION}:es:${region}:${account}:domain/*/*`
                ],
            }),
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: ['aoss:APIAccessAll'],
                resources: [
                    `arn:${Aws.PARTITION}:aoss:${region}:${account}:collection/*`
                ],
            }),
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: [
                    'secretsmanager:GetSecretValue',
                    'secretsmanager:DescribeSecret',
                ],
                resources: [
                    `arn:${Aws.PARTITION}:secretsmanager:${region}:${account}:secret:*`
                ],
            }),
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: [
                    's3:ListBucket',
                    "s3:CreateBucket",
                    "s3:DeleteBucket",
                ],
                resources: [migrationsBucketArn],
            }),
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: [
                    's3:GetObject',
                    's3:PutObject',
                    's3:DeleteObject',
                    "s3:AbortMultipartUpload",
                ],
                resources: [`${migrationsBucketArn}/*`],
            }),
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: [
                    "logs:DescribeLogStreams",
                    "logs:CreateLogGroup",
                ],
                resources: [migrationLogGroupArn],
            }),
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: [
                    "logs:PutLogEvents",
                    "logs:CreateLogStream"
                ],
                resources: [`${migrationLogGroupArn}:log-stream:*`],
            }),
            // Allow Console/tests to verify emitted CloudWatch application metrics
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: [
                    "cloudwatch:ListMetrics",
                    "cloudwatch:GetMetricData"
                ],
                // Classic CloudWatch metric queries require a wildcard resource.
                resources: ['*'],
                conditions: {
                    StringEquals: {
                        'aws:RequestedRegion': region,
                    },
                },
            }),
            // Sending traces to xray
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: [
                    "xray:PutTraceSegments",
                    "xray:PutTelemetryRecords"
                ],
                // These X-Ray actions do not support resource-level permissions.
                resources: ['*'],
                conditions: {
                    StringEquals: {
                        'aws:RequestedRegion': region,
                    },
                },
            }),
            // CloudWatch dashboard management for Helm post-install/pre-delete hooks
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: [
                    'cloudwatch:PutDashboard',
                    'cloudwatch:GetDashboard',
                    'cloudwatch:DeleteDashboards',
                ],
                resources: [
                    `arn:${Aws.PARTITION}:cloudwatch::${account}:dashboard/MA-${stageName}-${region}-CaptureReplay`,
                    `arn:${Aws.PARTITION}:cloudwatch::${account}:dashboard/MA-${stageName}-${region}-ReindexFromSnapshot`
                ],
            }),
            // ACM PCA permissions for TLS certificate issuance via cert-manager
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: [
                    'acm-pca:IssueCertificate',
                    'acm-pca:GetCertificate',
                    'acm-pca:DescribeCertificateAuthority',
                    'acm-pca:DeleteCertificateAuthority',
                    'acm-pca:UpdateCertificateAuthority',
                    'acm-pca:TagCertificateAuthority',
                ],
                resources: [
                    `arn:${Aws.PARTITION}:acm-pca:${region}:${account}:certificate-authority/*`
                ],
            }),
            // These PCA actions do not support resource-level permissions.
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: [
                    'acm-pca:ListCertificateAuthorities',
                    'acm-pca:CreateCertificateAuthority',
                ],
                resources: ['*'],
                conditions: {
                    StringEquals: {
                        'aws:RequestedRegion': region,
                    },
                },
            })
        );
        return podIdentityRole
    }
}
