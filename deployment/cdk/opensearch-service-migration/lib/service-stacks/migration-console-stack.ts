import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {MountPoint, Volume} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
import {createOpenSearchIAMAccessPolicy, createOpenSearchServerlessIAMAccessPolicy} from "../common-utilities";


export interface MigrationConsoleProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly fetchMigrationEnabled: boolean
}

export class MigrationConsoleStack extends MigrationServiceCore {

    constructor(scope: Construct, id: string, props: MigrationConsoleProps) {
        super(scope, id, props)
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceConnectSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceConnectSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "mskAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/mskAccessSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "defaultDomainAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/osAccessSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "replayerOutputAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/replayerOutputAccessSecurityGroupId`))
        ]
        const osClusterEndpoint = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/osClusterEndpoint`)
        const brokerEndpoints = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/mskBrokers`);

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

        const mskClusterARN = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/mskClusterARN`);
        const mskClusterName = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/mskClusterName`);
        const mskClusterAdminPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [mskClusterARN],
            actions: [
                "kafka-cluster:*"
            ]
        })
        const mskClusterAllTopicArn = `arn:aws:kafka:${props.env?.region}:${props.env?.account}:topic/${mskClusterName}/*`
        const mskTopicAdminPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [mskClusterAllTopicArn],
            actions: [
                "kafka-cluster:*"
            ]
        })
        const mskClusterAllGroupArn = `arn:aws:kafka:${props.env?.region}:${props.env?.account}:group/${mskClusterName}/*`
        const mskConsumerGroupAdminPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [mskClusterAllGroupArn],
            actions: [
                "kafka-cluster:*"
            ]
        })

        const environment: { [key: string]: string; } = {
            "MIGRATION_DOMAIN_ENDPOINT": osClusterEndpoint,
            "MIGRATION_KAFKA_BROKER_ENDPOINTS": brokerEndpoints
        }
        const openSearchPolicy = createOpenSearchIAMAccessPolicy(<string>props.env?.region, <string>props.env?.account)
        const openSearchServerlessPolicy = createOpenSearchServerlessIAMAccessPolicy(<string>props.env?.region, <string>props.env?.account)
        const taskRolePolicies = [mskClusterAdminPolicy, mskTopicAdminPolicy, mskConsumerGroupAdminPolicy, replayerOutputMountPolicy, openSearchPolicy, openSearchServerlessPolicy]

        if (props.fetchMigrationEnabled) {
            environment["FETCH_MIGRATION_COMMAND"] = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/fetchMigrationCommand`)

            const fetchMigrationTaskDefArn = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/fetchMigrationTaskDefArn`);
            const fetchMigrationTaskRunPolicy = new PolicyStatement({
                effect: Effect.ALLOW,
                resources: [fetchMigrationTaskDefArn],
                actions: [
                    "ecs:RunTask"
                ]
            })
            const fetchMigrationTaskRoleArn = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/fetchMigrationTaskRoleArn`);
            const fetchMigrationTaskExecRoleArn = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/fetchMigrationTaskExecRoleArn`);
            // Required as per https://docs.aws.amazon.com/AmazonECS/latest/userguide/task-iam-roles.html
            const fetchMigrationPassRolePolicy = new PolicyStatement({
                effect: Effect.ALLOW,
                resources: [fetchMigrationTaskRoleArn, fetchMigrationTaskExecRoleArn],
                actions: [
                    "iam:PassRole"
                ]
            })
            taskRolePolicies.push(fetchMigrationTaskRunPolicy)
            taskRolePolicies.push(fetchMigrationPassRolePolicy)
        }

        this.createService({
            serviceName: "migration-console",
            dockerFilePath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/src/main/docker/migrationConsole"),
            securityGroups: securityGroups,
            volumes: [replayerOutputEFSVolume],
            mountPoints: [replayerOutputMountPoint],
            environment: environment,
            taskRolePolicies: taskRolePolicies,
            taskCpuUnits: 512,
            taskMemoryLimitMiB: 1024,
            ...props
        });
    }

}