import {Construct} from 'constructs';
import {CfnCluster, CfnPodIdentityAssociation} from 'aws-cdk-lib/aws-eks';
import {IVpc, Port, SecurityGroup} from 'aws-cdk-lib/aws-ec2';
import {
    Effect,
    ManagedPolicy, Policy,
    PolicyStatement,
    Role,
    ServicePrincipal,
} from "aws-cdk-lib/aws-iam";
import {Fn, RemovalPolicy, Tags, Token} from "aws-cdk-lib";
import {Repository} from "aws-cdk-lib/aws-ecr";


export interface EKSInfraProps {
    vpc: IVpc;
    clusterName: string;
    ecrRepoName: string;
    stackName: string;
    vpcSubnetIds?: string[];
    vpcSecurityGroupIds?: string[];
    namespace?: string;
    buildImagesServiceAccountName?: string;
    argoWorkflowServiceAccountName?: string;
    migrationsServiceAccountName?: string;
}

export class EKSInfra extends Construct {
    public readonly cluster: CfnCluster;
    public readonly ecrRepo: Repository;

    constructor(scope: Construct, id: string, props: EKSInfraProps) {
        super(scope, id);

        const namespace = props.namespace ?? 'ma';
        const buildImagesServiceAccountName = props.buildImagesServiceAccountName ?? 'build-images-service-account';
        const argoWorkflowServiceAccountName = props.argoWorkflowServiceAccountName ?? 'argo-workflow-executor';
        const migrationsServiceAccountName = props.migrationsServiceAccountName ?? 'migrations-service-account';

        const migrationSecurityGroup = new SecurityGroup(this, 'MigrationsSecurityGroup', {
            vpc: props.vpc,
            allowAllOutbound: true,
            allowAllIpv6Outbound: true,
        })
        migrationSecurityGroup.addIngressRule(migrationSecurityGroup, Port.allTraffic());
        let securityGroupIds = [migrationSecurityGroup.securityGroupId]
        if (props.vpcSecurityGroupIds) {
            // Only add the inner join if it's safe (token or non-empty array)
            if (props.vpcSecurityGroupIds && (Token.isUnresolved(props.vpcSecurityGroupIds) || props.vpcSecurityGroupIds.length > 0)) {
                securityGroupIds.push(Fn.join(",", props.vpcSecurityGroupIds));
            }
            securityGroupIds = Fn.split(",", Fn.join(",", securityGroupIds));
        }

        this.ecrRepo = new Repository(this, 'MigrationsECRRepository', {
            repositoryName: props.ecrRepoName,
            removalPolicy: RemovalPolicy.DESTROY,
            emptyOnDelete: true
        });

        const clusterRole = new Role(this, 'MigrationsEKSClusterRole', {
            assumedBy: new ServicePrincipal('eks.amazonaws.com'),
            managedPolicies: [
                ManagedPolicy.fromAwsManagedPolicyName('AmazonEKSClusterPolicy'),
                ManagedPolicy.fromAwsManagedPolicyName('AmazonEKSBlockStoragePolicy'),
                ManagedPolicy.fromAwsManagedPolicyName('AmazonEKSComputePolicy'),
                ManagedPolicy.fromAwsManagedPolicyName('AmazonEKSLoadBalancingPolicy'),
                ManagedPolicy.fromAwsManagedPolicyName('AmazonEKSNetworkingPolicy'),
            ],
        });
        clusterRole.assumeRolePolicy?.addStatements(
            new PolicyStatement({
                effect: Effect.ALLOW,
                principals: [new ServicePrincipal('eks.amazonaws.com')],
                actions: ['sts:AssumeRole', 'sts:TagSession']
            }),
        );

        const ec2NodeRole = new Role(this, 'MigrationsEKSEC2NodeRole', {
            assumedBy: new ServicePrincipal('ec2.amazonaws.com'),
            managedPolicies: [
                ManagedPolicy.fromAwsManagedPolicyName('AmazonEKSWorkerNodePolicy'),
                ManagedPolicy.fromAwsManagedPolicyName('AmazonEC2ContainerRegistryReadOnly'),
                ManagedPolicy.fromAwsManagedPolicyName('AmazonEKS_CNI_Policy'),
            ],
        });

        let subnetIds
        if (props.vpcSubnetIds) {
            subnetIds = props.vpcSubnetIds
        } else {
            subnetIds = []
            for (const subnet of props.vpc.privateSubnets) {
                Tags.of(subnet).add(`kubernetes.io/cluster/${props.clusterName}`, 'shared');
                Tags.of(subnet).add('kubernetes.io/role/internal-elb', '1');
                subnetIds.push(subnet.subnetId)
            }
        }
        this.cluster = new CfnCluster(this, 'MigrationsEKSCluster', {
            name: props.clusterName,
            version: '1.32',
            upgradePolicy: {
                supportType: 'STANDARD'
            },
            roleArn: clusterRole.roleArn,
            resourcesVpcConfig: {
                subnetIds: subnetIds,
                endpointPrivateAccess: true,
                endpointPublicAccess: true,
                securityGroupIds: securityGroupIds
            },
            accessConfig: {
                authenticationMode: 'API',
            },
            computeConfig: {
                enabled: true,
                nodeRoleArn: ec2NodeRole.roleArn,
                nodePools: ["general-purpose", "system"]
            },
            storageConfig: {
                blockStorage: {
                    enabled: true
                }
            },
            kubernetesNetworkConfig: {
                elasticLoadBalancing: {
                    enabled: true
                }
            }
        });
        migrationSecurityGroup.addIngressRule(
            SecurityGroup.fromSecurityGroupId(this, "MigrationsEKSClusterDefaultSG", this.cluster.attrClusterSecurityGroupId),
            Port.allTraffic()
        );

        const podIdentityRole = this.createDefaultPodIdentityRole(props.clusterName)
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
        buildImagesPodIdentityAssociation.node.addDependency(this.cluster)
        argoWorkflowIdentityAssociation.node.addDependency(this.cluster)
        migrationsPodIdentityAssociation.node.addDependency(this.cluster)
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
            // Sending traces to xray
            new PolicyStatement({
                effect: Effect.ALLOW,
                actions: [
                    "xray:PutTraceSegments",
                    "xray:PutTelemetryRecords"
                ],
                resources: ['*'],
            }),
        );
        return podIdentityRole
    }
}