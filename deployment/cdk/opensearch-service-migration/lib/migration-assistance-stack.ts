import {RemovalPolicy, Stack} from "aws-cdk-lib";
import {Port, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {FileSystem, LifecyclePolicy, ThroughputMode} from 'aws-cdk-lib/aws-efs';
import {Construct} from "constructs";
import {CfnConfiguration} from "aws-cdk-lib/aws-msk";
import {Cluster} from "aws-cdk-lib/aws-ecs";
import {LogGroup, RetentionDays} from "aws-cdk-lib/aws-logs";
import {Bucket, BucketEncryption} from "aws-cdk-lib/aws-s3";
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

    createMSKResources(props: MigrationStackProps, streamingSecurityGroup: SecurityGroup) {
        // Create MSK cluster config
        const mskClusterConfig = new CfnConfiguration(this, "migrationMSKClusterConfig", {
            name: `migration-msk-config-${props.stage}`,
            serverProperties: "auto.create.topics.enable=true"
        })

        const mskLogGroup = new LogGroup(this, 'migrationMSKBrokerLogGroup',  {
            retention: RetentionDays.THREE_MONTHS
        });

        const brokerNodesPerAZ = props.mskBrokersPerAZCount ? props.mskBrokersPerAZCount : 1
        if (brokerNodesPerAZ < 1) {
            throw new Error(`The MSK context option 'mskBrokersPerAZCount' must be set to at least 1`)
        }

        const mskCluster = new MSKCluster(this, 'mskCluster', {
            clusterName: `migration-msk-cluster-${props.stage}`,
            kafkaVersion: KafkaVersion.V3_6_0,
            numberOfBrokerNodes: brokerNodesPerAZ,
            vpc: props.vpcDetails.vpc,
            vpcSubnets: props.vpcDetails.subnetSelection,
            securityGroups: [streamingSecurityGroup],
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
            removalPolicy: bucketRemovalPolicy,
            autoDeleteObjects: !!(props.artifactBucketRemovalPolicy && bucketRemovalPolicy === RemovalPolicy.DESTROY)
        });
        createMigrationStringParameter(this, artifactBucket.bucketArn, {
            ...props,
            parameter: MigrationSSMParameter.ARTIFACT_S3_ARN
        });

        new Cluster(this, 'migrationECSCluster', {
            vpc: props.vpcDetails.vpc,
            clusterName: `migration-${props.stage}-ecs-cluster`
        })

    }
}
