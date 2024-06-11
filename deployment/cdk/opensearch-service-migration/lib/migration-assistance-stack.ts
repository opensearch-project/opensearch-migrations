import {RemovalPolicy} from "aws-cdk-lib";
import {IPeer, IVpc, Peer, Port, SecurityGroup, SubnetFilter, SubnetType} from "aws-cdk-lib/aws-ec2";
import {FileSystem} from 'aws-cdk-lib/aws-efs';
import {Construct} from "constructs";
import {CfnCluster, CfnConfiguration} from "aws-cdk-lib/aws-msk";
import {Cluster} from "aws-cdk-lib/aws-ecs";
import {StackPropsExt} from "./stack-composer";
import {LogGroup, RetentionDays} from "aws-cdk-lib/aws-logs";
import {NamespaceType} from "aws-cdk-lib/aws-servicediscovery";
import {StreamingSourceType} from "./streaming-source-type";
import {Bucket, BucketEncryption} from "aws-cdk-lib/aws-s3";
import {parseRemovalPolicy} from "./common-utilities";
import { MigrationServiceCore, SSMParameter } from "./service-stacks";

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


export class MigrationAssistanceStack extends MigrationServiceCore {

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

    validateAndReturnVPCSubnetsForMSK(vpc: IVpc, brokerNodeCount: number, azCount: number, specifiedSubnetIds?: string[]): string[] {
        if (specifiedSubnetIds) {
            if (specifiedSubnetIds.length !== 2 && specifiedSubnetIds.length !== 3) {
                throw new Error(`MSK requires subnets for 2 or 3 AZs, but have detected ${specifiedSubnetIds.length} subnet ids provided with 'mskSubnetIds'`)
            }
            if (brokerNodeCount < 2 || brokerNodeCount % specifiedSubnetIds.length !== 0) {
                throw new Error(`The MSK broker node count (${brokerNodeCount} nodes inferred) must be a multiple of the number of 
                    AZs (${specifiedSubnetIds.length} AZs inferred from provided 'mskSubnetIds'). The node count can be set with the 'mskBrokerNodeCount' context option.`)
            }
            const selectSubnets = vpc.selectSubnets({
                subnetFilters: [SubnetFilter.byIds(specifiedSubnetIds)]
            })
            return selectSubnets.subnetIds
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
        this.createStringParameter(SSMParameter.MSK_CLUSTER_ARN, mskCluster.attrArn, props);
        this.createStringParameter(SSMParameter.MSK_CLUSTER_NAME, mskCluster.clusterName, props);
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
        this.createStringParameter(SSMParameter.TRAFFIC_STREAM_SOURCE_ACCESS_SECURITY_GROUP_ID, streamingSecurityGroup.securityGroupId, props);

        if (props.streamingSourceType === StreamingSourceType.AWS_MSK) {
            this.createMSKResources(props, streamingSecurityGroup)
        }

        const replayerOutputSG = new SecurityGroup(this, 'replayerOutputSG', {
            vpc: props.vpc,
            allowAllOutbound: false,
        });
        replayerOutputSG.addIngressRule(replayerOutputSG, Port.allTraffic());

        this.createStringParameter(SSMParameter.REPLAYER_OUTPUT_ACCESS_SECURITY_GROUP_ID, replayerOutputSG.securityGroupId, props);

        // Create an EFS file system for Traffic Replayer output
        const replayerOutputEFS = new FileSystem(this, 'replayerOutputEFS', {
            vpc: props.vpc,
            securityGroup: replayerOutputSG,
            removalPolicy: replayerEFSRemovalPolicy
        });
        this.createStringParameter(SSMParameter.REPLAYER_OUTPUT_EFS_ID, replayerOutputEFS.fileSystemId, props);

        const serviceSecurityGroup = new SecurityGroup(this, 'serviceSecurityGroup', {
            vpc: props.vpc,
            // Required for retrieving ECR image at service startup
            allowAllOutbound: true,
        })
        serviceSecurityGroup.addIngressRule(serviceSecurityGroup, Port.allTraffic());

        this.createStringParameter(SSMParameter.SERVICE_SECURITY_GROUP_ID, serviceSecurityGroup.securityGroupId, props);

        const artifactBucket = new Bucket(this, 'migrationArtifactsS3', {
            bucketName: `migration-artifacts-${this.account}-${props.stage}-${this.region}`,
            encryption: BucketEncryption.S3_MANAGED,
            enforceSSL: true,
            removalPolicy: bucketRemovalPolicy,
            autoDeleteObjects: !!(props.artifactBucketRemovalPolicy && bucketRemovalPolicy === RemovalPolicy.DESTROY)
        });
        this.createStringParameter(SSMParameter.ARTIFACT_S3_ARN, artifactBucket.bucketArn, props);

        const ecsCluster = new Cluster(this, 'migrationECSCluster', {
            vpc: props.vpc,
            clusterName: `migration-${props.stage}-ecs-cluster`
        })
        ecsCluster.addDefaultCloudMapNamespace( {
            name: `migration.${props.stage}.local`,
            type: NamespaceType.DNS_PRIVATE,
            vpc: props.vpc
        })
        const cloudMapNamespaceId = ecsCluster.defaultCloudMapNamespace!.namespaceId
        this.createStringParameter(SSMParameter.CLOUD_MAP_NAMESPACE_ID, cloudMapNamespaceId, props);
    }
}