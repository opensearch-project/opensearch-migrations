import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture, MountPoint, Volume} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {Effect, PolicyStatement, Role, ServicePrincipal} from "aws-cdk-lib/aws-iam";
import {
    createOpenSearchIAMAccessPolicy,
    createOpenSearchServerlessIAMAccessPolicy
} from "../common-utilities";
import {StreamingSourceType} from "../streaming-source-type";
import {LogGroup, RetentionDays} from "aws-cdk-lib/aws-logs";
import {RemovalPolicy} from "aws-cdk-lib";


export interface MigrationConsoleProps extends StackPropsExt {
    readonly migrationsSolutionVersion: string,
    readonly vpc: IVpc,
    readonly streamingSourceType: StreamingSourceType,
    readonly fetchMigrationEnabled: boolean,
    readonly fargateCpuArch: CpuArchitecture,
    readonly otelCollectorEnabled: boolean,
    readonly migrationConsoleEnableOSI: boolean
}

export class MigrationConsoleStack extends MigrationServiceCore {
    
    createMSKAdminIAMPolicies(stage: string, deployId: string): PolicyStatement[] {
        const mskClusterARN = StringParameter.valueForStringParameter(this, `/migration/${stage}/${deployId}/mskClusterARN`);
        const mskClusterName = StringParameter.valueForStringParameter(this, `/migration/${stage}/${deployId}/mskClusterName`);
        const mskClusterAdminPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [mskClusterARN],
            actions: [
                "kafka-cluster:*"
            ]
        })
        const mskClusterAllTopicArn = `arn:aws:kafka:${this.region}:${this.account}:topic/${mskClusterName}/*`
        const mskTopicAdminPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [mskClusterAllTopicArn],
            actions: [
                "kafka-cluster:*"
            ]
        })
        const mskClusterAllGroupArn = `arn:aws:kafka:${this.region}:${this.account}:group/${mskClusterName}/*`
        const mskConsumerGroupAdminPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [mskClusterAllGroupArn],
            actions: [
                "kafka-cluster:*"
            ]
        })
        return [mskClusterAdminPolicy, mskTopicAdminPolicy, mskConsumerGroupAdminPolicy]
    }

    configureOpenSearchIngestionPipelineRole(stage: string, deployId: string) {
        const osiPipelineRole = new Role(this, 'osisPipelineRole', {
            assumedBy: new ServicePrincipal('osis-pipelines.amazonaws.com'),
            description: 'OpenSearch Ingestion Pipeline role for OpenSearch Migrations'
        });
        // Add policy to allow access to Opensearch domains
        osiPipelineRole.addToPolicy(new PolicyStatement({
            effect: Effect.ALLOW,
            actions: ["es:DescribeDomain", "es:ESHttp*"],
            resources: [`arn:aws:es:${this.region}:${this.account}:domain/*`]
        }))

        new StringParameter(this, 'SSMParameterOSIPipelineRoleArn', {
            description: 'OpenSearch Migration Parameter for OpenSearch Ingestion Pipeline Role ARN',
            parameterName: `/migration/${stage}/${deployId}/osiPipelineRoleArn`,
            stringValue: osiPipelineRole.roleArn
        });
        return osiPipelineRole.roleArn
    }

    createOpenSearchIngestionManagementPolicy(pipelineRoleArn: string): PolicyStatement[] {
        const allMigrationPipelineArn = `arn:aws:osis:${this.region}:${this.account}:pipeline/*`
        const osiManagementPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [allMigrationPipelineArn],
            actions: [
                "osis:*"
            ]
        })
        const passPipelineRolePolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [pipelineRoleArn],
            actions: [
                "iam:PassRole"
            ]
        })
        return [osiManagementPolicy, passPipelineRolePolicy]
    }

    constructor(scope: Construct, id: string, props: MigrationConsoleProps) {
        super(scope, id, props)
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceConnectSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceConnectSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "trafficStreamSourceAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/trafficStreamSourceAccessSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "defaultDomainAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/osAccessSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "replayerOutputAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/replayerOutputAccessSecurityGroupId`))
        ]
        if (props.otelCollectorEnabled) {
            securityGroups.push(SecurityGroup.fromSecurityGroupId(this, "otelCollectorSGId", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/otelCollectorSGId`)))
        }

        const osClusterEndpoint = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/osClusterEndpoint`)
        const brokerEndpoints = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/kafkaBrokers`);

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
        const replayerOutputEFSArn = `arn:aws:elasticfilesystem:${this.region}:${this.account}:file-system/${volumeId}`
        const replayerOutputMountPolicy = new PolicyStatement( {
            effect: Effect.ALLOW,
            resources: [replayerOutputEFSArn],
            actions: [
                "elasticfilesystem:ClientMount",
                "elasticfilesystem:ClientWrite"
            ]
        })

        const ecsClusterArn = `arn:aws:ecs:${this.region}:${this.account}:service/migration-${props.stage}-ecs-cluster`
        const allReplayerServiceArn = `${ecsClusterArn}/migration-${props.stage}-traffic-replayer*`
        const reindexFromSnapshotServiceArn = `${ecsClusterArn}/migration-${props.stage}-reindex-from-snapshot`
        const ecsUpdateServicePolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [allReplayerServiceArn, reindexFromSnapshotServiceArn],
            actions: [
                "ecs:UpdateService"
            ]
        })

        const artifactS3Arn = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/artifactS3Arn`)
        const artifactS3AnyObjectPath = `${artifactS3Arn}/*`
        const artifactS3PublishPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [artifactS3AnyObjectPath],
            actions: [
                "s3:PutObject"
            ]
        })

        // Allow Console to determine proper subnets to use for any resource creation
        const describeVPCPolicy = new PolicyStatement( {
            effect: Effect.ALLOW,
            resources: ["*"],
            actions: [
                "ec2:DescribeSubnets",
                "ec2:DescribeRouteTables"
            ]
        })

        // Allow Console to retrieve SSM Parameters
        const getSSMParamsPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [`arn:aws:ssm:${this.region}:${this.account}:parameter/migration/${props.stage}/${props.defaultDeployId}/*`],
            actions: [
                "ssm:GetParameters"
            ]
        })

        const environment: { [key: string]: string; } = {
            "MIGRATION_DOMAIN_ENDPOINT": osClusterEndpoint,
            "MIGRATION_KAFKA_BROKER_ENDPOINTS": brokerEndpoints,
            "MIGRATION_STAGE": props.stage,
            "MIGRATION_SOLUTION_VERSION": props.migrationsSolutionVersion
        }
        const openSearchPolicy = createOpenSearchIAMAccessPolicy(this.region, this.account)
        const openSearchServerlessPolicy = createOpenSearchServerlessIAMAccessPolicy(this.region, this.account)
        let servicePolicies = [replayerOutputMountPolicy, openSearchPolicy, openSearchServerlessPolicy, ecsUpdateServicePolicy, artifactS3PublishPolicy, describeVPCPolicy, getSSMParamsPolicy]
        if (props.streamingSourceType === StreamingSourceType.AWS_MSK) {
            const mskAdminPolicies = this.createMSKAdminIAMPolicies(props.stage, props.defaultDeployId)
            servicePolicies = servicePolicies.concat(mskAdminPolicies)
        }
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
            servicePolicies.push(fetchMigrationTaskRunPolicy)
            servicePolicies.push(fetchMigrationPassRolePolicy)
        }

        if (props.migrationConsoleEnableOSI) {
            const pipelineRoleArn = this.configureOpenSearchIngestionPipelineRole(props.stage, props.defaultDeployId)
            servicePolicies.push(...this.createOpenSearchIngestionManagementPolicy(pipelineRoleArn))
            const osiLogGroup = new LogGroup(this, 'OSILogGroup',  {
                retention: RetentionDays.ONE_MONTH,
                removalPolicy: RemovalPolicy.DESTROY,
                logGroupName: `/migration/${props.stage}/${props.defaultDeployId}/openSearchIngestion`
            });
            new StringParameter(this, 'SSMParameterOSIPipelineLogGroupName', {
                description: 'OpenSearch Migration Parameter for OpenSearch Ingestion Pipeline Log Group Name',
                parameterName: `/migration/${props.stage}/${props.defaultDeployId}/osiPipelineLogGroupName`,
                stringValue: osiLogGroup.logGroupName
            });
        }

        this.createService({
            serviceName: "migration-console",
            dockerDirectoryPath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/src/main/docker/migrationConsole"),
            securityGroups: securityGroups,
            volumes: [replayerOutputEFSVolume],
            mountPoints: [replayerOutputMountPoint],
            environment: environment,
            taskRolePolicies: servicePolicies,
            cpuArchitecture: props.fargateCpuArch,
            taskCpuUnits: 512,
            taskMemoryLimitMiB: 1024,
            ...props
        });
    }

}