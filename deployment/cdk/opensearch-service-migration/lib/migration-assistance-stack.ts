import {Stack} from "aws-cdk-lib";
import {IVpc, Peer, Port, SecurityGroup, SubnetType} from "aws-cdk-lib/aws-ec2";
import {FileSystem} from 'aws-cdk-lib/aws-efs';
import {Construct} from "constructs";
import {CfnCluster, CfnConfiguration} from "aws-cdk-lib/aws-msk";
import {Cluster} from "aws-cdk-lib/aws-ecs";
import {StackPropsExt} from "./stack-composer";
import {LogGroup, RetentionDays} from "aws-cdk-lib/aws-logs";
import {NamespaceType} from "aws-cdk-lib/aws-servicediscovery";
import {StringParameter} from "aws-cdk-lib/aws-ssm";

export interface MigrationStackProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly trafficComparatorEnabled: boolean,
    // Future support needed to allow importing an existing MSK cluster
    readonly mskImportARN?: string,
    readonly mskEnablePublicEndpoints?: boolean
    readonly mskBrokerNodeCount?: number
}


export class MigrationAssistanceStack extends Stack {

    constructor(scope: Construct, id: string, props: MigrationStackProps) {
        super(scope, id, props);

        // Create MSK cluster config
        const mskClusterConfig = new CfnConfiguration(this, "migrationMSKClusterConfig", {
            name: `migration-msk-config-${props.stage}`,
            serverProperties: "auto.create.topics.enable=true"
        })

        const mskSecurityGroup = new SecurityGroup(this, 'migrationMSKSecurityGroup', {
            vpc: props.vpc,
            allowAllOutbound: false
        });
        // This will allow all ip access for all TCP ports by default when public access is enabled,
        // it should be updated if further restriction is desired
        if (props.mskEnablePublicEndpoints) {
            mskSecurityGroup.addIngressRule(Peer.anyIpv4(), Port.allTcp())
        }
        mskSecurityGroup.addIngressRule(mskSecurityGroup, Port.allTraffic())
        new StringParameter(this, 'SSMParameterMSKAccessGroupId', {
            description: 'OpenSearch migration parameter for MSK access security group id',
            parameterName: `/migration/${props.stage}/${props.defaultDeployId}/mskAccessSecurityGroupId`,
            stringValue: mskSecurityGroup.securityGroupId
        });

        const mskLogGroup = new LogGroup(this, 'migrationMSKBrokerLogGroup',  {
            retention: RetentionDays.THREE_MONTHS
        });

        // Create an MSK cluster
        const mskCluster = new CfnCluster(this, 'migrationMSKCluster', {
            clusterName: `migration-msk-cluster-${props.stage}`,
            kafkaVersion: '2.8.1',
            numberOfBrokerNodes: props.mskBrokerNodeCount ? props.mskBrokerNodeCount : 2,
            brokerNodeGroupInfo: {
                instanceType: 'kafka.m5.large',
                clientSubnets: props.vpc.selectSubnets({subnetType: SubnetType.PUBLIC}).subnetIds,
                connectivityInfo: {
                    // Public access cannot be enabled on cluster creation
                    publicAccess: {
                        type: "DISABLED"
                    }
                },
                securityGroups: [mskSecurityGroup.securityGroupId]
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
        new StringParameter(this, 'SSMParameterMSKARN', {
            description: 'OpenSearch Migration Parameter for MSK ARN',
            parameterName: `/migration/${props.stage}/${props.defaultDeployId}/mskClusterARN`,
            stringValue: mskCluster.attrArn
        });
        new StringParameter(this, 'SSMParameterMSKClusterName', {
            description: 'OpenSearch Migration Parameter for MSK cluster name',
            parameterName: `/migration/${props.stage}/${props.defaultDeployId}/mskClusterName`,
            stringValue: mskCluster.clusterName
        });

        if (props.trafficComparatorEnabled) {
            const comparatorSQLiteSG = new SecurityGroup(this, 'comparatorSQLiteSG', {
                vpc: props.vpc,
                allowAllOutbound: false,
            });
            comparatorSQLiteSG.addIngressRule(comparatorSQLiteSG, Port.allTraffic());
            new StringParameter(this, 'SSMParameterComparatorSQLAccessGroupId', {
                description: 'OpenSearch migration parameter for Comparator SQL volume access security group id',
                parameterName: `/migration/${props.stage}/${props.defaultDeployId}/comparatorSQLAccessSecurityGroupId`,
                stringValue: comparatorSQLiteSG.securityGroupId
            });

            // Create an EFS file system for the traffic-comparator
            const comparatorSQLiteEFS = new FileSystem(this, 'comparatorSQLiteEFS', {
                vpc: props.vpc,
                securityGroup: comparatorSQLiteSG
            });
            new StringParameter(this, 'SSMParameterComparatorSQLVolumeEFSId', {
                description: 'OpenSearch migration parameter for Comparator SQL EFS filesystem id',
                parameterName: `/migration/${props.stage}/${props.defaultDeployId}/comparatorSQLVolumeEFSId`,
                stringValue: comparatorSQLiteEFS.fileSystemId
            });
        }

        const replayerOutputSG = new SecurityGroup(this, 'replayerOutputSG', {
            vpc: props.vpc,
            allowAllOutbound: false,
        });
        replayerOutputSG.addIngressRule(replayerOutputSG, Port.allTraffic());

        new StringParameter(this, 'SSMParameterReplayerOutputAccessGroupId', {
            description: 'OpenSearch migration parameter for Replayer output access security group id',
            parameterName: `/migration/${props.stage}/${props.defaultDeployId}/replayerOutputAccessSecurityGroupId`,
            stringValue: replayerOutputSG.securityGroupId
        });

        // Create an EFS file system for Traffic Replayer output
        const replayerOutputEFS = new FileSystem(this, 'replayerOutputEFS', {
            vpc: props.vpc,
            securityGroup: replayerOutputSG
        });
        new StringParameter(this, 'SSMParameterReplayerOutputEFSId', {
            description: 'OpenSearch migration parameter for Replayer output EFS filesystem id',
            parameterName: `/migration/${props.stage}/${props.defaultDeployId}/replayerOutputEFSId`,
            stringValue: replayerOutputEFS.fileSystemId
        });

        const serviceConnectSecurityGroup = new SecurityGroup(this, 'serviceConnectSecurityGroup', {
            vpc: props.vpc,
            // Required for retrieving ECR image at service startup
            allowAllOutbound: true,
        })
        serviceConnectSecurityGroup.addIngressRule(serviceConnectSecurityGroup, Port.allTraffic());

        new StringParameter(this, 'SSMParameterServiceConnectGroupId', {
            description: 'OpenSearch migration parameter for Service Connect security group id',
            parameterName: `/migration/${props.stage}/${props.defaultDeployId}/serviceConnectSecurityGroupId`,
            stringValue: serviceConnectSecurityGroup.securityGroupId
        });

        const ecsCluster = new Cluster(this, 'migrationECSCluster', {
            vpc: props.vpc,
            clusterName: `migration-${props.stage}-ecs-cluster`
        })
        ecsCluster.addDefaultCloudMapNamespace( {
            name: `migration.${props.stage}.local`,
            type: NamespaceType.DNS_PRIVATE,
            useForServiceConnect: true,
            vpc: props.vpc
        })
        const cloudMapNamespaceId = ecsCluster.defaultCloudMapNamespace!.namespaceId
        new StringParameter(this, 'SSMParameterCloudMapNamespaceId', {
            description: 'OpenSearch migration parameter for Service Discovery CloudMap Namespace Id',
            parameterName: `/migration/${props.stage}/${props.defaultDeployId}/cloudMapNamespaceId`,
            stringValue: cloudMapNamespaceId
        });


    }
}