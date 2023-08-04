import {CfnOutput, Stack} from "aws-cdk-lib";
import {
    Instance,
    InstanceClass,
    InstanceSize,
    InstanceType,
    IVpc,
    MachineImage,
    Peer,
    Port,
    SecurityGroup,
    SubnetType
} from "aws-cdk-lib/aws-ec2";
import {FileSystem} from 'aws-cdk-lib/aws-efs';
import {Construct} from "constructs";
import {CfnCluster, CfnConfiguration} from "aws-cdk-lib/aws-msk";
import {StackPropsExt} from "./stack-composer";
import {LogGroup, RetentionDays} from "aws-cdk-lib/aws-logs";

export interface migrationStackProps extends StackPropsExt {
    readonly vpc: IVpc,
    // Future support needed to allow importing an existing MSK cluster
    readonly mskARN?: string,
    readonly mskEnablePublicEndpoints?: boolean
}


export class MigrationAssistanceStack extends Stack {

    public readonly mskARN: string;

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

        const mskLogGroup = new LogGroup(this, 'migrationMSKBrokerLogGroup',  {
            retention: RetentionDays.THREE_MONTHS
        });

        // Create an MSK cluster
        const mskCluster = new CfnCluster(this, 'migrationMSKCluster', {
            clusterName: 'migration-msk-cluster',
            kafkaVersion: '2.8.1',
            numberOfBrokerNodes: 2,
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

        // Create an EFS file system for Traffic Replayer output
        const replayerOutputEFS = new FileSystem(this, 'replayerOutputEFS', {
            vpc: props.vpc,
            securityGroup: replayerOutputSG
        });

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
            exportName: `${props.copilotAppName}-${props.stage}-msk-cluster-arn`,
            description: 'Migration MSK Cluster ARN'
        });

    }
}