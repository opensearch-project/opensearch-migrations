import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {MountPoint, Volume} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {
    createMSKConsumerIAMPolicies,
    createOpenSearchIAMAccessPolicy,
    createOpenSearchServerlessIAMAccessPolicy
} from "../common-utilities";
import {StreamingSourceType} from "../streaming-source-type";


export interface TrafficReplayerProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly enableClusterFGACAuth: boolean,
    readonly streamingSourceType: StreamingSourceType,
    readonly addOnMigrationId?: string,
    readonly customKafkaGroupId?: string,
    readonly userAgentSuffix?: string,
    readonly extraArgs?: string,
    readonly analyticsServiceEnabled?: boolean
}

export class TrafficReplayerStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: TrafficReplayerProps) {
        super(scope, id, props)
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceConnectSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceConnectSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "trafficStreamSourceAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/trafficStreamSourceAccessSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "defaultDomainAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/osAccessSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "replayerOutputAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/replayerOutputAccessSecurityGroupId`))
        ]

        const volumeName = "sharedReplayerOutputVolume"
        const volumeId = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/replayerOutputEFSId`)
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
        const replayerOutputEFSArn = `arn:aws:elasticfilesystem:${props.env?.region}:${props.env?.account}:file-system/${volumeId}`
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
        const openSearchPolicy = createOpenSearchIAMAccessPolicy(this.region, this.account)
        const openSearchServerlessPolicy = createOpenSearchServerlessIAMAccessPolicy(this.region, this.account)
        let servicePolicies = [replayerOutputMountPolicy, secretAccessPolicy, openSearchPolicy, openSearchServerlessPolicy]
        if (props.streamingSourceType === StreamingSourceType.AWS_MSK) {
            const mskConsumerPolicies = createMSKConsumerIAMPolicies(this, this.region, this.account, props.stage, props.defaultDeployId)
            servicePolicies = servicePolicies.concat(mskConsumerPolicies)
        }

        const deployId = props.addOnMigrationDeployId ? props.addOnMigrationDeployId : props.defaultDeployId
        const osClusterEndpoint = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${deployId}/osClusterEndpoint`)
        const brokerEndpoints = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/kafkaBrokers`);
        const groupId = props.customKafkaGroupId ? props.customKafkaGroupId : `logging-group-${deployId}`

        let replayerCommand = `/runJavaWithClasspath.sh org.opensearch.migrations.replay.TrafficReplayer ${osClusterEndpoint} --insecure --kafka-traffic-brokers ${brokerEndpoints} --kafka-traffic-topic logging-traffic-topic --kafka-traffic-group-id ${groupId}`
        if (props.enableClusterFGACAuth) {
            const osUserAndSecret = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${deployId}/osUserAndSecretArn`);
            replayerCommand = replayerCommand.concat(` --auth-header-user-and-secret ${osUserAndSecret}`)
        }
        replayerCommand = props.streamingSourceType === StreamingSourceType.AWS_MSK ? replayerCommand.concat(" --kafka-traffic-enable-msk-auth") : replayerCommand
        replayerCommand = props.userAgentSuffix ? replayerCommand.concat(` --user-agent ${props.userAgentSuffix}`) : replayerCommand
        replayerCommand = props.analyticsServiceEnabled ? replayerCommand.concat(" --otelCollectorEndpoint http://otel-collector:4317") : replayerCommand
        replayerCommand = props.extraArgs ? replayerCommand.concat(` ${props.extraArgs}`) : replayerCommand
        this.createService({
            serviceName: `traffic-replayer-${deployId}`,
            taskInstanceCount: 0,
            dockerFilePath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/build/docker/trafficReplayer"),
            dockerImageCommand: ['/bin/sh', '-c', replayerCommand],
            securityGroups: securityGroups,
            volumes: [replayerOutputEFSVolume],
            mountPoints: [replayerOutputMountPoint],
            taskRolePolicies: servicePolicies,
            environment: {
                "TUPLE_DIR_PATH": `/shared-replayer-output/traffic-replayer-${deployId}`
            },
            taskCpuUnits: 1024,
            taskMemoryLimitMiB: 4096,
            ...props
        });
    }

}