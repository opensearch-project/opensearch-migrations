import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture, MountPoint, Volume} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore, SSMParameter} from "./migration-service-core";
import {Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {
    createMSKConsumerIAMPolicies,
    createOpenSearchIAMAccessPolicy,
    createOpenSearchServerlessIAMAccessPolicy
} from "../common-utilities";
import {StreamingSourceType} from "../streaming-source-type";
import { Duration, Stack } from "aws-cdk-lib";
import {OtelCollectorSidecar} from "./migration-otel-collector-sidecar";


export interface TrafficReplayerProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly enableClusterFGACAuth: boolean,
    readonly streamingSourceType: StreamingSourceType,
    readonly fargateCpuArch: CpuArchitecture,
    readonly addOnMigrationId?: string,
    readonly customKafkaGroupId?: string,
    readonly userAgentSuffix?: string,
    readonly extraArgs?: string,
    readonly otelCollectorEnabled?: boolean,
    readonly maxUptime?: Duration
}

export class TrafficReplayerStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: TrafficReplayerProps) {
        super(scope, id, props)
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceSG", this.getStringParameter(SSMParameter.SERVICE_SECURITY_GROUP_ID, props)),
            SecurityGroup.fromSecurityGroupId(this, "trafficStreamSourceAccessSG", this.getStringParameter(SSMParameter.TRAFFIC_STREAM_SOURCE_ACCESS_SECURITY_GROUP_ID, props)),
            SecurityGroup.fromSecurityGroupId(this, "defaultDomainAccessSG", this.getStringParameter(SSMParameter.OS_ACCESS_SECURITY_GROUP_ID, props)),
            SecurityGroup.fromSecurityGroupId(this, "replayerOutputAccessSG", this.getStringParameter(SSMParameter.REPLAYER_OUTPUT_ACCESS_SECURITY_GROUP_ID, props))
        ]

        const volumeName = "sharedReplayerOutputVolume"
        const volumeId = this.getStringParameter(SSMParameter.REPLAYER_OUTPUT_EFS_ID, props)
        const replayerOutputEFSVolume: Volume = {
            name: volumeName,
            efsVolumeConfiguration: {
                fileSystemId: volumeId,
                transitEncryption: "ENABLED"
            }
        };
        const replayerOutputMountPoint: MountPoint = {
            containerPath: "/shared-replayer-output",
            readOnly: false,
            sourceVolume: volumeName
        }
        const replayerOutputEFSArn = `arn:${this.partition}:elasticfilesystem:${this.region}:${this.account}:file-system/${volumeId}`
        const replayerOutputMountPolicy = new PolicyStatement( {
            effect: Effect.ALLOW,
            resources: [replayerOutputEFSArn],
            actions: [
                "elasticfilesystem:ClientMount",
                "elasticfilesystem:ClientWrite"
            ]
        })
        
        const secretAccessPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: ["*"],
            actions: [
                "secretsmanager:GetSecretValue",
                "secretsmanager:DescribeSecret"
            ]
        })
        const openSearchPolicy = createOpenSearchIAMAccessPolicy(this.partition, this.region, this.account)
        const openSearchServerlessPolicy = createOpenSearchServerlessIAMAccessPolicy(this.partition, this.region, this.account)
        let servicePolicies = [replayerOutputMountPolicy, secretAccessPolicy, openSearchPolicy, openSearchServerlessPolicy]
        if (props.streamingSourceType === StreamingSourceType.AWS_MSK) {
            const mskConsumerPolicies = createMSKConsumerIAMPolicies(this, this.partition, this.region, this.account, props.stage, props.defaultDeployId)
            servicePolicies = servicePolicies.concat(mskConsumerPolicies)
        }

        const deployId = props.addOnMigrationDeployId ? props.addOnMigrationDeployId : props.defaultDeployId
        const osClusterEndpoint = this.getStringParameter(SSMParameter.OS_CLUSTER_ENDPOINT, props);
        const brokerEndpoints = this.getStringParameter(SSMParameter.KAFKA_BROKERS, props);
        const groupId = props.customKafkaGroupId ? props.customKafkaGroupId : `logging-group-${deployId}`

        let replayerCommand = `/runJavaWithClasspath.sh org.opensearch.migrations.replay.TrafficReplayer ${osClusterEndpoint} --insecure --kafka-traffic-brokers ${brokerEndpoints} --kafka-traffic-topic logging-traffic-topic --kafka-traffic-group-id ${groupId}`
        if (props.enableClusterFGACAuth) {
            const osUserAndSecret = this.getStringParameter(SSMParameter.OS_USER_AND_SECRET_ARN, props);
            replayerCommand = replayerCommand.concat(` --auth-header-user-and-secret ${osUserAndSecret}`)
        }
        replayerCommand = props.streamingSourceType === StreamingSourceType.AWS_MSK ? replayerCommand.concat(" --kafka-traffic-enable-msk-auth") : replayerCommand
        replayerCommand = props.userAgentSuffix ? replayerCommand.concat(` --user-agent ${props.userAgentSuffix}`) : replayerCommand
        replayerCommand = props.otelCollectorEnabled ? replayerCommand.concat(` --otelCollectorEndpoint http://localhost:${OtelCollectorSidecar.OTEL_CONTAINER_PORT}`) : replayerCommand
        replayerCommand = props.extraArgs ? replayerCommand.concat(` ${props.extraArgs}`) : replayerCommand
        this.createService({
            serviceName: `traffic-replayer-${deployId}`,
            taskInstanceCount: 0,
            dockerDirectoryPath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/build/docker/trafficReplayer"),
            dockerImageCommand: ['/bin/sh', '-c', replayerCommand],
            securityGroups: securityGroups,
            volumes: [replayerOutputEFSVolume],
            mountPoints: [replayerOutputMountPoint],
            taskRolePolicies: servicePolicies,
            environment: {
                "TUPLE_DIR_PATH": `/shared-replayer-output/traffic-replayer-${deployId}`
            },
            cpuArchitecture: props.fargateCpuArch,
            taskCpuUnits: 1024,
            taskMemoryLimitMiB: 4096,
            ...props
        });
    }
}