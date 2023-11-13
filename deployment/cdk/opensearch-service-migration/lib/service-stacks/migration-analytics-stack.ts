import {Stack} from "aws-cdk-lib";
import {StackPropsExt} from "../stack-composer";
import {
  BastionHostLinux,
  BlockDeviceVolume,
  MachineImage,
  Peer,
  Port,
  SecurityGroup,
  IVpc,
} from "aws-cdk-lib/aws-ec2";
import {MountPoint, PortMapping, Protocol, Volume} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {OpenSearchDomainStack} from "../opensearch-domain-stack";
import {EngineVersion} from "aws-cdk-lib/aws-opensearchservice";
import {EbsDeviceVolumeType} from "aws-cdk-lib/aws-ec2";

export interface MigrationAnalyticsProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly vpcSubnetIds?: string[],
    readonly vpcSecurityGroupIds?: string[],
    readonly availabilityZoneCount?: number,

    readonly extraArgs?: string,
    readonly engineVersion: EngineVersion,
    readonly dataNodeInstanceType?: string,
    readonly dataNodes?: number,
    readonly dedicatedManagerNodeType?: string,
    readonly dedicatedManagerNodeCount?: number,
    readonly warmInstanceType?: string,
    readonly warmNodes?: number,
    readonly enforceHTTPS?: boolean,
    readonly ebsEnabled?: boolean,
    readonly ebsIops?: number,
    readonly ebsVolumeSize?: number,
    readonly ebsVolumeType?: EbsDeviceVolumeType,
    readonly encryptionAtRestEnabled?: boolean,
    readonly encryptionAtRestKmsKeyARN?: string,
    readonly appLogEnabled?: boolean,
    readonly appLogGroup?: string,
    readonly nodeToNodeEncryptionEnabled?: boolean,
}

// The MigrationAnalyticsStack consists of the OpenTelemetry Collector ECS container & an
// OpenSearch cluster with dashboard.
export class MigrationAnalyticsStack extends MigrationServiceCore {

    openSearchAnalyticsStack: Stack

    constructor(scope: Construct, id: string, props: MigrationAnalyticsProps) {
        super(scope, id, props)

        // Bastion Security Group
        const bastionSecurityGroup = new SecurityGroup(
          this,
          "analyticsDashboardBastionSecurityGroup",
          {
            vpc: props.vpc,
            allowAllOutbound: true,
            securityGroupName: "analyticsDashboardBastionSecurityGroup",
          }
        );

        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceConnectSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceConnectSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "migrationAnalyticsSGId", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/analyticsDomainSGId`)),
            bastionSecurityGroup
        ]

        securityGroups[1].addIngressRule(bastionSecurityGroup, Port.tcp(443))

        this.createService({
            serviceName: `otel-collector`,
            dockerFilePath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/src/main/docker/otelcol"),
            securityGroups: securityGroups,
            taskCpuUnits: 1024,
            taskMemoryLimitMiB: 4096,
            ...props
        });

        // Bastion host to access Opensearch Dashboards
        new BastionHostLinux(this, "AnalyticsDashboardBastionHost", {
          vpc: props.vpc,
          securityGroup: bastionSecurityGroup,
          machineImage: MachineImage.latestAmazonLinux2023(),
          blockDevices: [
            {
              deviceName: "/dev/xvda",
              volume: BlockDeviceVolume.ebs(10, {
                encrypted: true,
              }),
            },
          ],
        });
    
        this.openSearchAnalyticsStack = new OpenSearchDomainStack(scope, `analyticsDomainStack`,
        {
            stackName: `OSMigrations-${props.stage}-${props.region}-AnalyticsDomain`,
            description: "This stack prepares the Migration Analytics OS Domain",
            version: props.engineVersion,
            domainName: "migration-analytics-domain",
            dataNodeInstanceType: props.dataNodeInstanceType,
            dataNodes: props.dataNodes,
            dedicatedManagerNodeType: props.dedicatedManagerNodeType,
            dedicatedManagerNodeCount: props.dedicatedManagerNodeCount,
            warmInstanceType: props.warmInstanceType,
            warmNodes: props.warmNodes,
            enableDemoAdmin: false,
            enforceHTTPS: props.enforceHTTPS,
            ebsEnabled: props.ebsEnabled,
            ebsIops: props.ebsIops,
            ebsVolumeSize: props.ebsVolumeSize,
            ebsVolumeType: props.ebsVolumeType,
            encryptionAtRestEnabled: props.encryptionAtRestEnabled,
            encryptionAtRestKmsKeyARN: props.encryptionAtRestKmsKeyARN,
            appLogEnabled: props.appLogEnabled,
            appLogGroup: props.appLogGroup,
            nodeToNodeEncryptionEnabled: props.nodeToNodeEncryptionEnabled,
            vpcSubnetIds: props.vpcSubnetIds,
            vpcSecurityGroupIds: props.vpcSecurityGroupIds,
            availabilityZoneCount: props.availabilityZoneCount,
            domainAccessSecurityGroupParameter: "analyticsDomainSGId",
            endpointParameterName: "analyticsDomainEndpoint",
            // Note that the usual thing here would be `...props`, but that was somehow causing the
            // stackName to be overridden, so both the general analytics stack & the analytics domain stack
            // had the same stackName, which was causing a lot of CFN deployment issues. The necessary
            // inhereted props (stage & defaultDeployId) are specified manually instead.
            stage: props.stage,
            defaultDeployId: props.defaultDeployId
        })
    }

}