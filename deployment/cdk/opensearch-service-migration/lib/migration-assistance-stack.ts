import {RemovalPolicy, Stack} from "aws-cdk-lib";
import {IVpc, Port, SecurityGroup, SubnetFilter, SubnetType} from "aws-cdk-lib/aws-ec2";
import {FileSystem} from 'aws-cdk-lib/aws-efs';
import {Construct} from "constructs";
import {CfnConfiguration} from "aws-cdk-lib/aws-msk";
import {Cluster} from "aws-cdk-lib/aws-ecs";
import {StackPropsExt} from "./stack-composer";
import {LogGroup, RetentionDays} from "aws-cdk-lib/aws-logs";
import {StreamingSourceType} from "./streaming-source-type";
import {Bucket, BucketEncryption} from "aws-cdk-lib/aws-s3";
import {createMigrationStringParameter, MigrationSSMParameter, parseRemovalPolicy} from "./common-utilities";
import {
    ClientAuthentication,
    ClientBrokerEncryption,
    Cluster as MSKCluster,
    ClusterMonitoringLevel,
    KafkaVersion
} from "@aws-cdk/aws-msk-alpha";
import {SelectedSubnets} from "aws-cdk-lib/aws-ec2/lib/vpc";
import {KafkaYaml} from "./migration-services-yaml";

export interface MigrationStackProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly streamingSourceType: StreamingSourceType,
    // Future support needed to allow importing an existing MSK cluster
    readonly mskImportARN?: string,
    readonly mskBrokersPerAZCount?: number,
    readonly mskSubnetIds?: string[],
    readonly mskAZCount?: number,
    readonly replayerOutputEFSRemovalPolicy?: string
    readonly artifactBucketRemovalPolicy?: string
}


export class MigrationAssistanceStack extends Stack {
    kafkaYaml: KafkaYaml;

    // This function exists to overcome the limitation on the vpc.selectSubnets() call which requires the subnet
    // type to be provided or else an empty list will be returned if public subnets are provided, thus this function
    // tries different subnet types if unable to select the provided subnetIds
    selectSubnetsFromTypes(vpc: IVpc, subnetIds: string[]): SelectedSubnets {
        const subnetsTypeList = [SubnetType.PRIVATE_WITH_EGRESS, SubnetType.PUBLIC, SubnetType.PRIVATE_ISOLATED]
        for (const subnetType of subnetsTypeList) {
            const subnets = vpc.selectSubnets({
                subnetType: subnetType,
                subnetFilters: [SubnetFilter.byIds(subnetIds)]
            })
            if (subnets.subnetIds.length == subnetIds.length) {
                return subnets
            }
        }
        throw Error(`Unable to find subnet ids: ${subnetIds} in VPC: ${vpc.vpcId}. Please ensure all subnet ids exist and are of the same subnet type`)
    }

    validateAndReturnVPCSubnetsForMSK(vpc: IVpc, brokerNodeCount: number, azCount: number, specifiedSubnetIds?: string[]): SelectedSubnets {
        if (specifiedSubnetIds) {
            if (specifiedSubnetIds.length !== 2 && specifiedSubnetIds.length !== 3) {
                throw new Error(`MSK requires subnets for 2 or 3 AZs, but have detected ${specifiedSubnetIds.length} subnet ids provided with 'mskSubnetIds'`)
            }
            if (brokerNodeCount < 2 || brokerNodeCount % specifiedSubnetIds.length !== 0) {
                throw new Error(`The MSK broker node count (${brokerNodeCount} nodes inferred) must be a multiple of the number of 
                    AZs (${specifiedSubnetIds.length} AZs inferred from provided 'mskSubnetIds'). The node count can be set with the 'mskBrokersPerAZCount' context option.`)
            }
            return this.selectSubnetsFromTypes(vpc, specifiedSubnetIds)
        }
        if (azCount !== 2 && azCount !== 3) {
            throw new Error(`MSK requires subnets for 2 or 3 AZs, but have detected an AZ count of ${azCount} has been provided with 'mskAZCount'`)
        }
        if (brokerNodeCount < 2 || brokerNodeCount % azCount !== 0) {
            throw new Error(`The MSK broker node count (${brokerNodeCount} nodes inferred) must be a multiple of the number of 
                AZs (${azCount} AZs inferred from provided 'mskAZCount'). The node count can be set with the 'mskBrokersPerAZCount' context option.`)
        }

        let uniqueAzPrivateSubnets: SelectedSubnets|undefined
        if (vpc.privateSubnets.length > 0) {
            uniqueAzPrivateSubnets = vpc.selectSubnets({
                subnetType: SubnetType.PRIVATE_WITH_EGRESS,
                onePerAz: true
            })
        }
        if (uniqueAzPrivateSubnets && uniqueAzPrivateSubnets.subnetIds.length >= azCount) {
            const desiredSubnetIds = uniqueAzPrivateSubnets.subnetIds.sort().slice(0, azCount)
            return vpc.selectSubnets({
                subnetFilters: [
                    SubnetFilter.byIds(desiredSubnetIds)
                ]
            })
        }
        else {
            throw new Error(`Not enough AZs available for private subnets in VPC to meet desired ${azCount} AZs. The AZ count can be specified with the 'mskAZCount' context option`)
        }
    }

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
        const mskAZs = props.mskAZCount ? props.mskAZCount : 2
        const subnets = this.validateAndReturnVPCSubnetsForMSK(props.vpc, brokerNodesPerAZ * mskAZs, mskAZs, props.mskSubnetIds)

        const mskCluster = new MSKCluster(this, 'mskCluster', {
            clusterName: `migration-msk-cluster-${props.stage}`,
            kafkaVersion: KafkaVersion.V3_6_0,
            numberOfBrokerNodes: brokerNodesPerAZ,
            vpc: props.vpc,
            vpcSubnets: subnets,
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
            vpc: props.vpc,
            allowAllOutbound: false
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
            vpc: props.vpc,
            allowAllOutbound: false,
        });
        sharedLogsSG.addIngressRule(sharedLogsSG, Port.allTraffic());

        createMigrationStringParameter(this, sharedLogsSG.securityGroupId, {
            ...props,
            parameter: MigrationSSMParameter.SHARED_LOGS_SECURITY_GROUP_ID
        });

        // Create an EFS file system for Traffic Replayer output
        const sharedLogsEFS = new FileSystem(this, 'sharedLogsEFS', {
            vpc: props.vpc,
            securityGroup: sharedLogsSG,
            removalPolicy: replayerEFSRemovalPolicy
        });
        createMigrationStringParameter(this, sharedLogsEFS.fileSystemId, {
            ...props,
            parameter: MigrationSSMParameter.SHARED_LOGS_EFS_ID
        });

        const serviceSecurityGroup = new SecurityGroup(this, 'serviceSecurityGroup', {
            vpc: props.vpc,
            // Required for retrieving ECR image at service startup
            allowAllOutbound: true,
        })
        serviceSecurityGroup.addIngressRule(serviceSecurityGroup, Port.allTraffic());

        createMigrationStringParameter(this, serviceSecurityGroup.securityGroupId, {
            ...props,
            parameter: MigrationSSMParameter.SERVICE_SECURITY_GROUP_ID
        });

        const artifactBucket = new Bucket(this, 'migrationArtifactsS3', {
            bucketName: `migration-artifacts-${this.account}-${props.stage}-${this.region}`,
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
            vpc: props.vpc,
            clusterName: `migration-${props.stage}-ecs-cluster`
        })

    }
}
