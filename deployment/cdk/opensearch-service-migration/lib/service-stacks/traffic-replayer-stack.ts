import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {MountPoint, PortMapping, Protocol, Volume} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
import {StringParameter} from "aws-cdk-lib/aws-ssm";


export interface TrafficReplayerProps extends StackPropsExt {
    readonly vpc: IVpc,
}

export class TrafficReplayerStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: TrafficReplayerProps) {
        super(scope, id, props)
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceConnectSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/serviceConnectSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "mskAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/mskAccessSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "defaultDomainAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/osAccessSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "replayerOutputAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/replayerAccessSecurityGroupId`))
        ]

        const volumeName = "sharedReplayerOutputVolume"
        const replayerOutputEFSVolume: Volume = {
            name: volumeName,
            efsVolumeConfiguration: {
                fileSystemId: StringParameter.valueForStringParameter(this, `/migration/${props.stage}/replayerOutputEFSId`),
                transitEncryption: "ENABLED"
            }
        };
        const replayerOutputMountPoint: MountPoint = {
            containerPath: "/shared-replayer-output",
            readOnly: false,
            sourceVolume: volumeName
        }

        const mskClusterARN = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/mskClusterARN`);
        const mskClusterConnectPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [mskClusterARN],
            actions: [
                "kafka-cluster:Connect"
            ]
        })
        // Ideally we should have something like this, but this is actually working on a token value:
        // let mskClusterAllTopicArn = mskClusterARN.replace(":cluster", ":topic").concat("/*")
        const mskTopicConsumerPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: ["*"],
            actions: [
                "kafka-cluster:DescribeTopic",
                "kafka-cluster:ReadData"
            ]
        })
        const mskConsumerGroupPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: ["*"],
            actions: [
                "kafka-cluster:AlterGroup",
                "kafka-cluster:DescribeGroup"
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

        // TODO make these values dynamic for multiple replayers
        const osClusterEndpoint = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/osClusterEndpoint`)
        const brokerEndpoints = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/mskBrokers`);
        const osUserAndSecret = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/osUserAndSecretArn`);
        const groupId = "logging-group-default"
        const extraArgs = `--auth-header-user-and-secret ${osUserAndSecret}`
        const replayerCommand = `/runJavaWithClasspath.sh org.opensearch.migrations.replay.TrafficReplayer ${osClusterEndpoint} --insecure --kafka-traffic-brokers ${brokerEndpoints} --kafka-traffic-topic logging-traffic-topic --kafka-traffic-group-id ${groupId} --kafka-traffic-enable-msk-auth ${extraArgs}`
        this.createService({
            serviceName: "traffic-replayer",
            dockerFilePath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/build/docker/trafficReplayer/Dockerfile"),
            dockerImageCommand: ['/bin/sh', '-c', replayerCommand],
            securityGroups: securityGroups,
            volumes: [replayerOutputEFSVolume],
            mountPoints: [replayerOutputMountPoint],
            taskRolePolicies: [mskClusterConnectPolicy, mskTopicConsumerPolicy, mskConsumerGroupPolicy, secretAccessPolicy],
            environment: {
                "TUPLE_DIR_PATH": "/shared-replayer-output/traffic-replayer-default"
            },
            taskCpuUnits: 1024,
            taskMemoryLimitMiB: 4096,
            ...props
        });
    }

}