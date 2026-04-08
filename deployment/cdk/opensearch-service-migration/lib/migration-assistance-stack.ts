import {RemovalPolicy, Stack} from "aws-cdk-lib";
import {Port, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {FileSystem, LifecyclePolicy, ThroughputMode} from 'aws-cdk-lib/aws-efs';
import {Construct} from "constructs";
import {CfnConfiguration} from "aws-cdk-lib/aws-msk";
import {Cluster} from "aws-cdk-lib/aws-ecs";
import {LogGroup, RetentionDays} from "aws-cdk-lib/aws-logs";
import {Bucket, BucketEncryption} from "aws-cdk-lib/aws-s3";
import {Effect, PolicyStatement, Role, ServicePrincipal} from "aws-cdk-lib/aws-iam";
import {AwsCustomResource, AwsCustomResourcePolicy, PhysicalResourceId} from "aws-cdk-lib/custom-resources";
import {
    ScalableTarget, 
    TargetTrackingScalingPolicy, 
    PredefinedMetric, 
    ServiceNamespace
} from 'aws-cdk-lib/aws-applicationautoscaling'; 
import {
    createMigrationStringParameter,
    MigrationSSMParameter,
    parseRemovalPolicy
} from "./common-utilities";
import {
    ClientAuthentication,
    ClientBrokerEncryption,
    Cluster as MSKCluster,
    ClusterMonitoringLevel,
    KafkaVersion
} from "@aws-cdk/aws-msk-alpha";

import {VpcDetails} from "./network-stack";
import {StackPropsExt} from "./stack-composer";
import {StreamingSourceType} from "./streaming-source-type";
import {KafkaYaml} from "./migration-services-yaml";

export interface MigrationStackProps extends StackPropsExt {
    readonly vpcDetails: VpcDetails,
    readonly streamingSourceType: StreamingSourceType,
    // Future support needed to allow importing an existing MSK cluster
    readonly mskImportARN?: string,
    readonly mskBrokersPerAZCount?: number,
    readonly replayerOutputEFSRemovalPolicy?: string
    readonly artifactBucketRemovalPolicy?: string
}


export class MigrationAssistanceStack extends Stack {
    kafkaYaml: KafkaYaml;
    artifactBucketName: string;
    clusterArn: string;
    clusterName: string;
    bootstrapBrokers: string;


    createMSKResources(props: MigrationStackProps, streamingSecurityGroup: SecurityGroup) {
        const storageContext = this.node.tryGetContext('MskEbsStorage') ?? {};
        const maxCapacity = storageContext.maxCapacity ?? 16384; // Maximum capacity for each MSK broker node
        // Create MSK cluster config
        const mskClusterConfig = new CfnConfiguration(this, "migrationMSKClusterConfig", {
            name: `migration-msk-config-${props.stage}`,
            serverProperties: "auto.create.topics.enable=true"
        })

        const mskLogGroup = new LogGroup(this, 'migrationMSKBrokerLogGroup',  {
            retention: RetentionDays.THREE_MONTHS
        });

        const brokerNodesPerAZ = props.mskBrokersPerAZCount ?? 1
        if (brokerNodesPerAZ < 1) {
            throw new Error(`The MSK context option 'mskBrokersPerAZCount' must be set to at least 1`)
        }

        const mskCluster = new MSKCluster(this, 'mskCluster', {
            clusterName: `migration-msk-cluster-${props.stage}`,
            kafkaVersion: KafkaVersion.V3_9_X,
            numberOfBrokerNodes: brokerNodesPerAZ,
            vpc: props.vpcDetails.vpc,
            vpcSubnets: props.vpcDetails.subnetSelection,
            securityGroups: [streamingSecurityGroup],
            ebsStorageInfo: {
                volumeSize: 1750 // Starting capacity for each MSK broker node
            },
            configurationInfo: {
                arn: mskClusterConfig.attrArn,
                // Current limitation of alpha construct, would like to get latest revision dynamically
                revision: 1
            },
            encryptionInTransit: {
                clientBroker: ClientBrokerEncryption.TLS,
                enableInCluster: true
            },
            clientAuthentication: ClientAuthentication.sasl({
                iam: true,
            }),
            logging: {
                cloudwatchLogGroup: mskLogGroup
            },
            monitoring: {
                clusterMonitoringLevel: ClusterMonitoringLevel.DEFAULT
            },
            removalPolicy: RemovalPolicy.DESTROY
        });

            const scalableTarget = new ScalableTarget(this, 'MSKScalableTarget', {
                serviceNamespace: ServiceNamespace.KAFKA,
                scalableDimension: 'kafka:broker-storage:VolumeSize',
                resourceId: mskCluster.clusterArn,
                minCapacity: 1,
                maxCapacity: maxCapacity,
            });

            new TargetTrackingScalingPolicy(this, 'MSKScalingPolicy', {
                scalingTarget: scalableTarget,
                predefinedMetric: PredefinedMetric.KAFKA_BROKER_STORAGE_UTILIZATION,
                targetValue: 70,
                disableScaleIn: true,
            });


        createMigrationStringParameter(this, mskCluster.clusterArn, {
            ...props,
            parameter: MigrationSSMParameter.MSK_CLUSTER_ARN
        });
        createMigrationStringParameter(this, mskCluster.clusterName, {
            ...props,
            parameter: MigrationSSMParameter.MSK_CLUSTER_NAME
        });
        createMigrationStringParameter(this, mskCluster.bootstrapBrokersSaslIam, {
            ...props,
            parameter: MigrationSSMParameter.KAFKA_BROKERS
        });

        this.kafkaYaml = new KafkaYaml();
        this.kafkaYaml.msk = '';
        this.kafkaYaml.broker_endpoints = mskCluster.bootstrapBrokersSaslIam;

    }

    constructor(scope: Construct, id: string, props: MigrationStackProps) {
        super(scope, id, props);

        const bucketRemovalPolicy = parseRemovalPolicy('artifactBucketRemovalPolicy', props.artifactBucketRemovalPolicy)
        const replayerEFSRemovalPolicy = parseRemovalPolicy('replayerOutputEFSRemovalPolicy', props.replayerOutputEFSRemovalPolicy)

        const streamingSecurityGroup = new SecurityGroup(this, 'trafficStreamSourceSG', {
            vpc: props.vpcDetails.vpc,
            allowAllOutbound: false,
            allowAllIpv6Outbound: false,
        });
        streamingSecurityGroup.addIngressRule(streamingSecurityGroup, Port.allTraffic())
        createMigrationStringParameter(this, streamingSecurityGroup.securityGroupId, {
            ...props,
            parameter: MigrationSSMParameter.TRAFFIC_STREAM_SOURCE_ACCESS_SECURITY_GROUP_ID
        });

        if (props.streamingSourceType === StreamingSourceType.AWS_MSK) {
            this.createMSKResources(props, streamingSecurityGroup)
        }

        const sharedLogsSG = new SecurityGroup(this, 'sharedLogsSG', {
            vpc: props.vpcDetails.vpc,
            allowAllOutbound: false,
            allowAllIpv6Outbound: false,
        });
        sharedLogsSG.addIngressRule(sharedLogsSG, Port.allTraffic());

        createMigrationStringParameter(this, sharedLogsSG.securityGroupId, {
            ...props,
            parameter: MigrationSSMParameter.SHARED_LOGS_SECURITY_GROUP_ID
        });

        // Create an EFS file system for Traffic Replayer output
        const sharedLogsEFS = new FileSystem(this, 'sharedLogsEFS', {
            vpc: props.vpcDetails.vpc,
            vpcSubnets: props.vpcDetails.subnetSelection,
            securityGroup: sharedLogsSG,
            removalPolicy: replayerEFSRemovalPolicy,
            lifecyclePolicy: LifecyclePolicy.AFTER_1_DAY, // Cost break even is at 26 downloads / month
            throughputMode: ThroughputMode.BURSTING, // Best cost characteristics for write heavy, short-lived data
        });
        createMigrationStringParameter(this, sharedLogsEFS.fileSystemId, {
            ...props,
            parameter: MigrationSSMParameter.SHARED_LOGS_EFS_ID
        });

        const serviceSecurityGroup = new SecurityGroup(this, 'serviceSecurityGroup', {
            vpc: props.vpcDetails.vpc,
            // Required for retrieving ECR image at service startup
            allowAllOutbound: true,
            allowAllIpv6Outbound: true,
        })
        serviceSecurityGroup.addIngressRule(serviceSecurityGroup, Port.allTraffic());

        createMigrationStringParameter(this, serviceSecurityGroup.securityGroupId, {
            ...props,
            parameter: MigrationSSMParameter.SERVICE_SECURITY_GROUP_ID
        });

        this.artifactBucketName = `migration-artifacts-${this.account}-${props.stage}-${this.region}`
        const artifactBucket = new Bucket(this, 'migrationArtifactsS3', {
            bucketName: this.artifactBucketName,
            encryption: BucketEncryption.S3_MANAGED,
            enforceSSL: true,
            versioned: true, // Required for S3 Files
            removalPolicy: bucketRemovalPolicy,
            autoDeleteObjects: !!(props.artifactBucketRemovalPolicy && bucketRemovalPolicy === RemovalPolicy.DESTROY)
        });
        createMigrationStringParameter(this, artifactBucket.bucketArn, {
            ...props,
            parameter: MigrationSSMParameter.ARTIFACT_S3_ARN
        });

        // S3 Files: Create an IAM role, file system, security group, and mount targets
        // so that snapshot data can be accessed via NFS instead of per-pod mount-s3.
        const s3FilesRole = new Role(this, 's3FilesRole', {
            assumedBy: new ServicePrincipal('elasticfilesystem.amazonaws.com'),
            description: 'Role for S3 Files to sync with the artifact S3 bucket',
        });
        s3FilesRole.addToPolicy(new PolicyStatement({
            effect: Effect.ALLOW,
            actions: ['s3:ListBucket', 's3:ListBucketVersions'],
            resources: [artifactBucket.bucketArn],
        }));
        s3FilesRole.addToPolicy(new PolicyStatement({
            effect: Effect.ALLOW,
            actions: [
                's3:AbortMultipartUpload', 's3:DeleteObject*',
                's3:GetObject*', 's3:List*', 's3:PutObject*'
            ],
            resources: [`${artifactBucket.bucketArn}/*`],
        }));
        s3FilesRole.addToPolicy(new PolicyStatement({
            effect: Effect.ALLOW,
            actions: [
                'events:DeleteRule', 'events:DisableRule', 'events:EnableRule',
                'events:PutRule', 'events:PutTargets', 'events:RemoveTargets'
            ],
            resources: ['arn:aws:events:*:*:rule/DO-NOT-DELETE-S3-Files*'],
            conditions: { StringEquals: { 'events:ManagedBy': 'elasticfilesystem.amazonaws.com' } },
        }));
        s3FilesRole.addToPolicy(new PolicyStatement({
            effect: Effect.ALLOW,
            actions: [
                'events:DescribeRule', 'events:ListRuleNamesByTarget',
                'events:ListRules', 'events:ListTargetsByRule'
            ],
            resources: ['arn:aws:events:*:*:rule/*'],
        }));

        // Security group for S3 Files mount targets (NFS port 2049)
        const s3FilesSG = new SecurityGroup(this, 's3FilesMountTargetSG', {
            vpc: props.vpcDetails.vpc,
            description: 'Security group for S3 Files mount targets',
            allowAllOutbound: false,
        });
        s3FilesSG.addIngressRule(serviceSecurityGroup, Port.tcp(2049), 'NFS from migration services');
        s3FilesSG.addIngressRule(s3FilesSG, Port.tcp(2049), 'NFS within S3 Files SG');

        // Create S3 Files file system via custom resource (no L2 construct yet)
        const s3FilesFileSystem = new AwsCustomResource(this, 's3FilesFileSystem', {
            onCreate: {
                service: 'S3Files',
                action: 'createFileSystem',
                parameters: {
                    bucket: artifactBucket.bucketArn,
                    roleArn: s3FilesRole.roleArn,
                    acceptBucketWarning: true,
                    tags: [{ key: 'Name', value: `migration-s3files-${props.stage}` }],
                },
                physicalResourceId: PhysicalResourceId.fromResponse('fileSystemId'),
            },
            onDelete: {
                service: 'S3Files',
                action: 'deleteFileSystem',
                parameters: {
                    // PhysicalResourceId.fromResponse stores the fileSystemId from onCreate;
                    // on delete, the custom resource framework passes it as the physical resource ID.
                    // We reference it via getResponseField which reads the stored onCreate response.
                    fileSystemId: new PhysicalResourceId('dummy').id, // placeholder — see installLatestAwsSdk
                    forceDelete: true,
                },
            },
            installLatestAwsSdk: true,
            policy: AwsCustomResourcePolicy.fromStatements([
                new PolicyStatement({
                    effect: Effect.ALLOW,
                    actions: ['s3files:CreateFileSystem', 's3files:DeleteFileSystem', 's3files:GetFileSystem'],
                    resources: ['*'],
                }),
                new PolicyStatement({
                    effect: Effect.ALLOW,
                    actions: ['iam:PassRole'],
                    resources: [s3FilesRole.roleArn],
                }),
            ]),
        });

        const fileSystemId = s3FilesFileSystem.getResponseField('fileSystemId');

        // Create mount targets in each selected subnet.
        // Mount targets are automatically deleted when the file system is deleted with forceDelete=true.
        const subnets = props.vpcDetails.vpc.selectSubnets(props.vpcDetails.subnetSelection).subnets;
        for (let i = 0; i < subnets.length; i++) {
            new AwsCustomResource(this, `s3FilesMountTarget${i}`, {
                onCreate: {
                    service: 'S3Files',
                    action: 'createMountTarget',
                    parameters: {
                        fileSystemId: fileSystemId,
                        subnetId: subnets[i].subnetId,
                        securityGroups: [s3FilesSG.securityGroupId],
                    },
                    physicalResourceId: PhysicalResourceId.fromResponse('mountTargetId'),
                },
                // No onDelete — mount targets are cleaned up when the file system is force-deleted
                installLatestAwsSdk: true,
                policy: AwsCustomResourcePolicy.fromStatements([
                    new PolicyStatement({
                        effect: Effect.ALLOW,
                        actions: [
                            's3files:CreateMountTarget', 's3files:DeleteMountTarget',
                            's3files:GetMountTarget', 's3files:ListMountTargets'
                        ],
                        resources: ['*'],
                    }),
                    new PolicyStatement({
                        effect: Effect.ALLOW,
                        actions: ['ec2:DescribeSubnets', 'ec2:DescribeSecurityGroups',
                            'ec2:CreateNetworkInterface', 'ec2:DeleteNetworkInterface'],
                        resources: ['*'],
                    }),
                ]),
            });
        }

        createMigrationStringParameter(this, fileSystemId, {
            ...props,
            parameter: MigrationSSMParameter.S3_FILES_FILE_SYSTEM_ID
        });

        new Cluster(this, 'migrationECSCluster', {
            vpc: props.vpcDetails.vpc,
            clusterName: `migration-${props.stage}-ecs-cluster`
        })

    }
}
