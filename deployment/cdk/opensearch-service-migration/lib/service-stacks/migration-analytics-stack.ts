import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {MountPoint, PortMapping, Protocol, Volume} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
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
    readonly nodeToNodeEncryptionEnabled?: boolean
}

// The MigrationAnalyticsStack consists of the OpenTelemetry Collector ECS container & an
// OpenSearch cluster with dashboard.
export class MigrationAnalyticsStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: MigrationAnalyticsProps) {
        super(scope, id, props)

        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceConnectSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceConnectSecurityGroupId`)),
            // SecurityGroup.fromSecurityGroupId(this, "analyticsDomainAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/osAnalyticsAccessSecurityGroupId`)),
        ]

        this.createService({
            serviceName: `otel-collector`,
            dockerFilePath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/src/main/docker/otelcol"),
            securityGroups: securityGroups,
            taskCpuUnits: 1024,
            taskMemoryLimitMiB: 4096,
            ...props
        });
    
        const openSearchAnalyticsStack = new OpenSearchDomainStack(scope, `openSearchAnalyticsStack`,
        {
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
            domainAccessSecurityGroupParameter: "analyticsDomainSecurityGroupId",
            endpointParameterName: "analyticsDomainEndpoint",
            ...props
        })
    }

}