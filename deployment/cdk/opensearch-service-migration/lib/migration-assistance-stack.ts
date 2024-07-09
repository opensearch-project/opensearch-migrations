import {RemovalPolicy, Stack} from "aws-cdk-lib";
import {
    IPeer,
    IVpc,
    Peer,
    Port,
    SecurityGroup,
    SubnetFilter,
    SubnetType
} from "aws-cdk-lib/aws-ec2";
import {FileSystem} from 'aws-cdk-lib/aws-efs';
import {Construct} from "constructs";
import {CfnCluster, CfnConfiguration} from "aws-cdk-lib/aws-msk";
import {Cluster} from "aws-cdk-lib/aws-ecs";
import {StackPropsExt} from "./stack-composer";
import {LogGroup, RetentionDays} from "aws-cdk-lib/aws-logs";
import {StreamingSourceType} from "./streaming-source-type";
import {Bucket, BucketEncryption} from "aws-cdk-lib/aws-s3";
import {createMigrationStringParameter, MigrationSSMParameter, parseRemovalPolicy} from "./common-utilities";

export interface MigrationStackProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly streamingSourceType: StreamingSourceType,
    // Future support needed to allow importing an existing MSK cluster
    readonly mskImportARN?: string,
    readonly mskEnablePublicEndpoints?: boolean,
    readonly mskRestrictPublicAccessTo?: string,
    readonly mskRestrictPublicAccessType?: string,
    readonly mskBrokerNodeCount?: number,
    readonly mskSubnetIds?: string[],
    readonly mskAZCount?: number,
    readonly replayerOutputEFSRemovalPolicy?: string
    readonly artifactBucketRemovalPolicy?: string
}


export class MigrationAssistanceStack extends Stack {

    getPublicEndpointAccess(restrictPublicAccessTo: string, restrictPublicAccessType: string): IPeer {
        switch (restrictPublicAccessType) {
            case 'ipv4':
                return Peer.ipv4(restrictPublicAccessTo);
            case 'ipv6':
                return Peer.ipv6(restrictPublicAccessTo);
            case 'prefixList':
                return Peer.prefixList(restrictPublicAccessTo);
            case 'securityGroupId':
                return Peer.securityGroupId(restrictPublicAccessTo);
            default:
                throw new Error('mskRestrictPublicAccessType should be one of the below values: ipv4, ipv6, prefixList or securityGroupId');
        }
    }

    // This function exists to overcome the limitation on the vpc.selectSubnets() call which requires the subnet
    // type to be provided or else an empty list will be returned if public subnets are provided, thus this function
    // tries different subnet types if unable to select the provided subnetIds
    selectSubnetsFromTypes(vpc: IVpc, subnetIds: string[]): string[] {
        const subnetsTypeList = [SubnetType.PRIVATE_WITH_EGRESS, SubnetType.PUBLIC, SubnetType.PRIVATE_ISOLATED]
        for (const subnetType of subnetsTypeList) {
            const subnets = vpc.selectSubnets({
                subnetType: subnetType,
                subnetFilters: [SubnetFilter.byIds(subnetIds)]
            })
            if (subnets.subnetIds.length == subnetIds.length) {
                return subnets.subnetIds
            }
        }
        throw Error(`Unable to find subnet ids: ${subnetIds} in VPC: ${vpc.vpcId}. Please ensure all subnet ids exist and are of the same subnet type`)
    }

    validateAndReturnVPCSubnetsForMSK(vpc: IVpc, brokerNodeCount: number, azCount: number, specifiedSubnetIds?: string[]): string[] {
        if (specifiedSubnetIds) {
            if (specifiedSubnetIds.length !== 2 && specifiedSubnetIds.length !== 3) {
                throw new Error(`MSK requires subnets for 2 or 3 AZs, but have detected ${specifiedSubnetIds.length} subnet ids provided with 'mskSubnetIds'`)
            }
            if (brokerNodeCount < 2 || brokerNodeCount % specifiedSubnetIds.length !== 0) {
                throw new Error(`The MSK broker node count (${brokerNodeCount} nodes inferred) must be a multiple of the number of 
                    AZs (${specifiedSubnetIds.length} AZs inferred from provided 'mskSubnetIds'). The node count can be set with the 'mskBrokerNodeCount' context option.`)
            }
            return this.selectSubnetsFromTypes(vpc, specifiedSubnetIds)
        }
        if (azCount !== 2 && azCount !== 3) {
            throw new Error(`MSK requires subnets for 2 or 3 AZs, but have detected an AZ count of ${azCount} has been provided with 'mskAZCount'`)
        }
        if (brokerNodeCount < 2 || brokerNodeCount % azCount !== 0) {
            throw new Error(`The MSK broker node count (${brokerNodeCount} nodes inferred) must be a multiple of the number of 
                AZs (${azCount} AZs inferred from provided 'mskAZCount'). The node count can be set with the 'mskBrokerNodeCount' context option.`)
        }

        let uniqueAzPrivateSubnets: string[] = []
        if (vpc.privateSubnets.length > 0) {
            uniqueAzPrivateSubnets = vpc.selectSubnets({
                subnetType: SubnetType.PRIVATE_WITH_EGRESS,
                onePerAz: true
            }).subnetIds
        }
        let desiredSubnets
        if (uniqueAzPrivateSubnets.length >= azCount) {
            desiredSubnets = uniqueAzPrivateSubnets.sort().slice(0, azCount)
        }
        else {
            throw new Error(`Not enough AZs available for private subnets in VPC to meet desired ${azCount} AZs. The AZ count can be specified with the 'mskAZCount' context option`)
        }
        return desiredSubnets
    }

    createMSKResources(props: MigrationStackProps, streamingSecurityGroup: SecurityGroup) {
        // Create MSK cluster config
        const mskClusterConfig = new CfnConfiguration(this, "migrationMSKClusterConfig", {
            name: `migration-msk-config-${props.stage}`,
            serverProperties: "auto.create.topics.enable=true"
        })

        if (props.mskEnablePublicEndpoints && props.mskRestrictPublicAccessTo && props.mskRestrictPublicAccessType) {
            streamingSecurityGroup.addIngressRule(this.getPublicEndpointAccess(props.mskRestrictPublicAccessTo, props.mskRestrictPublicAccessType), Port.allTcp())
        }

        const mskLogGroup = new LogGroup(this, 'migrationMSKBrokerLogGroup',  {
            retention: RetentionDays.THREE_MONTHS
        });

        const brokerNodes = props.mskBrokerNodeCount ? props.mskBrokerNodeCount : 2
        const mskAZs = props.mskAZCount ? props.mskAZCount : 2
        const subnets = this.validateAndReturnVPCSubnetsForMSK(props.vpc, brokerNodes, mskAZs, props.mskSubnetIds)

        // Create an MSK cluster
        const mskCluster = new CfnCluster(this, 'migrationMSKCluster', {
            clusterName: `migration-msk-cluster-${props.stage}`,
            kafkaVersion: '3.6.0',
            numberOfBrokerNodes: brokerNodes,
            brokerNodeGroupInfo: {
                instanceType: 'kafka.m5.large',
                clientSubnets: subnets,
                connectivityInfo: {
                    // Public access cannot be enabled on cluster creation
                    publicAccess: {
                        type: "DISABLED"
                    }
                },
                securityGroups: [streamingSecurityGroup.securityGroupId]
            },
            configurationInfo: {
                arn: mskClusterConfig.attrArn,
                // Current limitation of using L1 construct, would like to get latest revision dynamically
                revision: 1
            },
            encryptionInfo: {
                encryptionInTransit: {
                    clientBroker: 'TLS',
                    inCluster: true
                },
            },
            enhancedMonitoring: 'DEFAULT',
            clientAuthentication: {
                sasl: {
                    iam: {
                        enabled: true
                    }
                },
                unauthenticated: {
                    enabled: false
                }
            },
            loggingInfo: {
                brokerLogs: {
                    cloudWatchLogs: {
                        enabled: true,
                        logGroup: mskLogGroup.logGroupName
                    }
                }
            }
        });
        createMigrationStringParameter(this, mskCluster.attrArn, {
            ...props,
            parameter: MigrationSSMParameter.MSK_CLUSTER_ARN
        });
        createMigrationStringParameter(this, mskCluster.clusterName, {
            ...props,
            parameter: MigrationSSMParameter.MSK_CLUSTER_NAME
        });
    }

    constructor(scope: Construct, id: string, props: MigrationStackProps) {
        super(scope, id, props);

        if (props.mskEnablePublicEndpoints && (!props.mskRestrictPublicAccessTo || !props.mskRestrictPublicAccessType)) {
            throw new Error("The 'mskEnablePublicEndpoints' option requires both 'mskRestrictPublicAccessTo' and 'mskRestrictPublicAccessType' options to be provided")
        }

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

        const replayerOutputSG = new SecurityGroup(this, 'replayerOutputSG', {
            vpc: props.vpc,
            allowAllOutbound: false,
        });
        replayerOutputSG.addIngressRule(replayerOutputSG, Port.allTraffic());

        createMigrationStringParameter(this, replayerOutputSG.securityGroupId, {
            ...props,
            parameter: MigrationSSMParameter.REPLAYER_OUTPUT_ACCESS_SECURITY_GROUP_ID
        });

        // Create an EFS file system for Traffic Replayer output
        const replayerOutputEFS = new FileSystem(this, 'replayerOutputEFS', {
            vpc: props.vpc,
            securityGroup: replayerOutputSG,
            removalPolicy: replayerEFSRemovalPolicy
        });
        createMigrationStringParameter(this, replayerOutputEFS.fileSystemId, {
            ...props,
            parameter: MigrationSSMParameter.REPLAYER_OUTPUT_EFS_ID
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
