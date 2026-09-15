import {Construct} from 'constructs';
import {CfnPodIdentityAssociation} from 'aws-cdk-lib/aws-eks';
import * as eks from 'aws-cdk-lib/aws-eks-v2';
import {IVpc, Subnet} from 'aws-cdk-lib/aws-ec2';
import {
    Effect,
    ManagedPolicy, Policy,
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
    stackName: string;
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

        this.allowAutoModeTagPropagation();

        // Grant EKS readonly access to all IAM principals in the account
        this.cluster.grantAccess('AccountReadonlyAccess',
            `arn:${Aws.PARTITION}:iam::${Stack.of(this).account}:root`,
            [eks.AccessPolicy.fromAccessPolicyName('AmazonEKSViewPolicy', {
                accessScopeType: eks.AccessScopeType.CLUSTER,
            })],
        );

        const podIdentityRole = this.createDefaultPodIdentityRole(props.clusterName)
        this.snapshotRole = new Role(scope, `SnapshotRole`, {
            assumedBy: new ServicePrincipal('es.amazonaws.com'),  // Note that snapshots are not currently possible on AOSS
            description: 'Role that grants OpenSearch Service permissions to access S3 to create snapshots',
            roleName: `${props.clusterName}-snapshot-role`
        });
        this.snapshotRole.addToPolicy(new PolicyStatement({
            effect: Effect.ALLOW,
            actions: ['s3:ListBucket'],
            resources: [`arn:${Aws.PARTITION}:s3:::migrations-*`],
        }));
        this.snapshotRole.addToPolicy(new PolicyStatement({
            effect: Effect.ALLOW,
            actions: ['s3:GetObject', 's3:PutObject', 's3:DeleteObject'],
            resources: [`arn:${Aws.PARTITION}:s3:::migrations-*/*`],
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

    /**
     * Lets EKS Auto Mode stamp user-defined tags onto the AWS resources it provisions on the
     * cluster's behalf: EC2 instances, launch templates, EBS volumes, snapshots, ENIs, load
     * balancers, target groups and load balancer security groups.
     *
     * None of those resources are created by CloudFormation, so CloudFormation stack tags never
     * reach them. Auto Mode applies the tags declared in a `NodeClass` `spec.tags` and in a
     * `StorageClass` `tagSpecification_N` parameter instead — but the `AmazonEKS*` managed
     * policies on the cluster role only permit the `eks:`-prefixed tags that Auto Mode adds for
     * itself. Without the statements below, a single user tag turns every `RunInstances` and
     * `CreateVolume` into an `AccessDenied` and the cluster silently stops scaling.
     *
     * Every statement is conditioned on the request carrying this cluster's own
     * `eks:eks-cluster-name` tag, which Auto Mode always sets and which is matched against the
     * session tag on the assumed cluster role — so this grants nothing outside the cluster.
     *
     * @see https://docs.aws.amazon.com/eks/latest/userguide/auto-learn-iam.html
     */
    private allowAutoModeTagPropagation() {
        // Auto Mode assumes the cluster role with `eks:eks-cluster-name` as a session tag, so this
        // resolves to the cluster making the call and cannot be spoofed by the request itself.
        const ownCluster = {
            'aws:RequestTag/eks:eks-cluster-name': '${aws:PrincipalTag/eks:eks-cluster-name}',
        };
        new Policy(this, 'AutoModeTagPropagationPolicy', {
            // aws-bootstrap.sh asserts this same named policy when --tags is used so older,
            // adopted, and hand-built clusters receive the prerequisite without a manual IAM step.
            policyName: 'AutoModeTagPropagationPolicy',
            roles: [this.cluster.role],
            statements: [
                new PolicyStatement({
                    sid: 'Compute',
                    effect: Effect.ALLOW,
                    actions: ['ec2:CreateFleet', 'ec2:RunInstances', 'ec2:CreateLaunchTemplate'],
                    resources: ['*'],
                    conditions: {
                        StringEquals: ownCluster,
                        StringLike: {
                            'aws:RequestTag/eks:kubernetes-node-class-name': '*',
                            'aws:RequestTag/eks:kubernetes-node-pool-name': '*',
                        },
                    },
                }),
                new PolicyStatement({
                    sid: 'Storage',
                    effect: Effect.ALLOW,
                    actions: ['ec2:CreateVolume', 'ec2:CreateSnapshot'],
                    resources: [
                        `arn:${Aws.PARTITION}:ec2:*:*:volume/*`,
                        `arn:${Aws.PARTITION}:ec2:*:*:snapshot/*`,
                    ],
                    conditions: { StringEquals: ownCluster },
                }),
                new PolicyStatement({
                    sid: 'Networking',
                    effect: Effect.ALLOW,
                    actions: ['ec2:CreateNetworkInterface'],
                    resources: ['*'],
                    conditions: {
                        StringEquals: ownCluster,
                        StringLike: { 'aws:RequestTag/eks:kubernetes-cni-node-name': '*' },
                    },
                }),
                new PolicyStatement({
                    sid: 'LoadBalancer',
                    effect: Effect.ALLOW,
                    actions: [
                        'elasticloadbalancing:CreateLoadBalancer',
                        'elasticloadbalancing:CreateTargetGroup',
                        'elasticloadbalancing:CreateListener',
                        'elasticloadbalancing:CreateRule',
                        'ec2:CreateSecurityGroup',
                    ],
                    resources: ['*'],
                    conditions: { StringEquals: ownCluster },
                }),
                new PolicyStatement({
                    sid: 'Shield',
                    effect: Effect.ALLOW,
                    actions: ['shield:CreateProtection', 'shield:TagResource'],
                    resources: [`arn:${Aws.PARTITION}:shield::*:protection/*`],
                    conditions: { StringEquals: ownCluster },
                }),
            ],
        });
    }

    createDefaultPodIdentityRole(clusterName: string) {
        const podIdentityRole = new Role(this, 'MigrationsPodIdentityRole', {
            roleName: `${clusterName}-migrations-role`,
            description: 'Migrations IAM role assumed by pods via EKS Pod Identity',
            assumedBy: new ServicePrincipal('pods.eks.amazonaws.com'),
            managedPolicies: [
                ManagedPolicy.fromAwsManagedPolicyName('AmazonEC2ContainerRegistryFullAccess'),
            ],
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
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: [
                    'ecr:GetAuthorizationToken',
                    'ecr:BatchGetImage',
                    'ecr:GetDownloadUrlForLayer',
                    'ecr:DescribeRepositories',
                    'ecr:BatchCheckLayerAvailability',
                    'ecr:CompleteLayerUpload',
                    'ecr:InitiateLayerUpload',
                    'ecr:PutImage',
                    'ecr:UploadLayerPart',
                ],
                resources: ['*'],
            }),
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: [
                    'elasticfilesystem:ClientMount',
                    'elasticfilesystem:ClientWrite',
                ],
                resources: ['*'],
            }),
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: ['es:ESHttp*', 'aoss:APIAccessAll'],
                resources: ['*'],
            }),
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: [
                    'secretsmanager:GetSecretValue',
                    'secretsmanager:DescribeSecret',
                    'secretsmanager:ListSecrets',
                ],
                resources: ['*'],
            }),
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: [
                    's3:GetObject',
                    's3:PutObject',
                    's3:ListBucket',
                    's3:ListAllMyBuckets',
                    's3:DeleteObject',
                    "s3:DeleteObjectVersion",
                    "s3:ListBucketVersions",
                    "s3:ListBucketMultipartUploads",
                    "s3:AbortMultipartUpload",
                    "s3:CreateBucket",
                    "s3:DeleteBucket",
                    "s3:ListBucket"
                ],
                resources: ['*'],
            }),
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: [
                    "logs:PutLogEvents",
                    "logs:DescribeLogStreams",
                    "logs:DescribeLogGroups",
                    "logs:CreateLogGroup",
                    "logs:CreateLogStream"
                ],
                resources: ['*'],
            }),
            // Allow Console/tests to verify emitted CloudWatch application metrics
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: [
                    "cloudwatch:ListMetrics",
                    "cloudwatch:GetMetricData"
                ],
                resources: ['*'],
            }),
            // Sending traces to xray
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: [
                    "xray:PutTraceSegments",
                    "xray:PutTelemetryRecords"
                ],
                resources: ['*'],
            }),
            // Allow passing default or user-provided snapshot role to OpenSearch service
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: ['iam:PassRole'],
                resources: [`arn:${Aws.PARTITION}:iam::${Stack.of(this).account}:role/*`]
            }),
            // CloudWatch dashboard management for Helm post-install/pre-delete hooks
            // Note: dashboard resource type has no condition keys in IAM; scoped by ARN prefix MA-*
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: [
                    'cloudwatch:PutDashboard',
                    'cloudwatch:GetDashboard',
                    'cloudwatch:DeleteDashboards',
                ],
                resources: [
                    `arn:${Aws.PARTITION}:cloudwatch::${Stack.of(this).account}:dashboard/MA-*`
                ],
            }),
            // ACM PCA permissions for TLS certificate issuance via cert-manager
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: [
                    'acm-pca:IssueCertificate',
                    'acm-pca:GetCertificate',
                    'acm-pca:DescribeCertificateAuthority',
                    'acm-pca:ListCertificateAuthorities',
                    'acm-pca:CreateCertificateAuthority',
                    'acm-pca:DeleteCertificateAuthority',
                    'acm-pca:UpdateCertificateAuthority',
                    'acm-pca:TagCertificateAuthority',
                ],
                resources: ['*'],
            })
        );
        return podIdentityRole
    }
}
