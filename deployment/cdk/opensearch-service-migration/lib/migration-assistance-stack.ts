import {CfnOutput, Names, Stack} from "aws-cdk-lib";
import {ISecurityGroup, IVpc, Peer, Port, SecurityGroup, SubnetType} from "aws-cdk-lib/aws-ec2";
import {FileSystem} from 'aws-cdk-lib/aws-efs';
import {Construct} from "constructs";
import {CfnCluster, CfnConfiguration} from "aws-cdk-lib/aws-msk";
import {Cluster} from "aws-cdk-lib/aws-ecs";
import {StackPropsExt} from "./stack-composer";
import {LogGroup, RetentionDays} from "aws-cdk-lib/aws-logs";
import {NamespaceType} from "aws-cdk-lib/aws-servicediscovery";

export interface migrationStackProps extends StackPropsExt {
    readonly vpc: IVpc,
    // Future support needed to allow importing an existing MSK cluster
    readonly mskImportARN?: string,
    readonly mskEnablePublicEndpoints?: boolean
    readonly mskBrokerNodeCount?: number
}


export class MigrationAssistanceStack extends Stack {

    public readonly mskARN: string;
    public readonly mskAccessSecurityGroup: ISecurityGroup;
    public readonly ecsCluster: Cluster;
    public readonly replayerOutputFileSystemId: string;
    public readonly replayerOutputAccessSecurityGroup: ISecurityGroup;
    public readonly serviceConnectSecurityGroup: ISecurityGroup;

    constructor(scope: Construct, id: string, props: migrationStackProps) {
        super(scope, id, props);

        // Create MSK cluster config
        const mskClusterConfig = new CfnConfiguration(this, "migrationMSKClusterConfig", {
            name: 'migration-msk-config',
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
        this.mskAccessSecurityGroup = mskSecurityGroup

        const mskLogGroup = new LogGroup(this, 'migrationMSKBrokerLogGroup',  {
            retention: RetentionDays.THREE_MONTHS
        });

        // Create an MSK cluster
        const mskCluster = new CfnCluster(this, 'migrationMSKCluster', {
            clusterName: 'migration-msk-cluster',
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
        this.mskARN = mskCluster.attrArn

        const comparatorSQLiteSG = new SecurityGroup(this, 'comparatorSQLiteSG', {
            vpc: props.vpc,
            allowAllOutbound: false,
        });
        comparatorSQLiteSG.addIngressRule(comparatorSQLiteSG, Port.allTraffic());

        // Create an EFS file system for the traffic-comparator
        const comparatorSQLiteEFS = new FileSystem(this, 'comparatorSQLiteEFS', {
            vpc: props.vpc,
            securityGroup: comparatorSQLiteSG
        });

        const replayerOutputSG = new SecurityGroup(this, 'replayerOutputSG', {
            vpc: props.vpc,
            allowAllOutbound: false,
        });
        replayerOutputSG.addIngressRule(replayerOutputSG, Port.allTraffic());
        this.replayerOutputAccessSecurityGroup = replayerOutputSG

        // Create an EFS file system for Traffic Replayer output
        const replayerOutputEFS = new FileSystem(this, 'replayerOutputEFS', {
            vpc: props.vpc,
            securityGroup: replayerOutputSG
        });
        this.replayerOutputFileSystemId = replayerOutputEFS.fileSystemId

        this.serviceConnectSecurityGroup = new SecurityGroup(this, 'serviceConnectSecurityGroup', {
            vpc: props.vpc,
            // Required for retrieving ECR image at service startup
            allowAllOutbound: true,
        })
        this.serviceConnectSecurityGroup.addIngressRule(replayerOutputSG, Port.allTraffic());
        this.ecsCluster = new Cluster(this, 'migrationECSCluster', {
            vpc: props.vpc,

            clusterName: `migration-${props.stage}-ecs-cluster`
        })
        this.ecsCluster.addDefaultCloudMapNamespace( {
            name: `migration.${props.stage}.local`,
            type: NamespaceType.DNS_PRIVATE,
            useForServiceConnect: true,
            vpc: props.vpc
        })

        let publicSubnetString = props.vpc.publicSubnets.map(_ => _.subnetId).join(",")
        let privateSubnetString = props.vpc.privateSubnets.map(_ => _.subnetId).join(",")
        const exports = [
            `export MIGRATION_VPC_ID=${props.vpc.vpcId}`,
            `export MIGRATION_CAPTURE_MSK_SG_ID=${mskSecurityGroup.securityGroupId}`,
            `export MIGRATION_COMPARATOR_EFS_ID=${comparatorSQLiteEFS.fileSystemId}`,
            `export MIGRATION_COMPARATOR_EFS_SG_ID=${comparatorSQLiteSG.securityGroupId}`,
            `export MIGRATION_REPLAYER_OUTPUT_EFS_ID=${replayerOutputEFS.fileSystemId}`,
            `export MIGRATION_REPLAYER_OUTPUT_EFS_SG_ID=${replayerOutputSG.securityGroupId}`]
        if (publicSubnetString) exports.push(`export MIGRATION_PUBLIC_SUBNETS=${publicSubnetString}`)
        if (privateSubnetString) exports.push(`export MIGRATION_PRIVATE_SUBNETS=${privateSubnetString}`)

        new CfnOutput(this, 'CopilotMigrationExports', {
            value: exports.join(";"),
            description: 'Exported migration resource values created by CDK that are needed by Copilot container deployments',
        });
        // Create export of MSK cluster ARN for Copilot stacks to use
        new CfnOutput(this, 'migrationMSKClusterARN', {
            value: mskCluster.attrArn,
            exportName: `${props.copilotAppName}-${props.copilotEnvName}-msk-cluster-arn`,
            description: 'Migration MSK Cluster ARN'
        });

    }
}