import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup, Port, ISecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture, PortMapping, Protocol, MountPoint, Volume} from "aws-cdk-lib/aws-ecs";
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
import { ServicesYaml } from "../migration-services-yaml";

export interface MigrationConsoleProps extends StackPropsExt {
    readonly migrationsSolutionVersion: string,
    readonly vpc: IVpc,
    readonly streamingSourceType: StreamingSourceType,
    readonly fetchMigrationEnabled: boolean,
    readonly fargateCpuArch: CpuArchitecture,
    readonly migrationConsoleEnableOSI: boolean,
    readonly migrationAPIEnabled?: boolean,
    readonly servicesYaml: ServicesYaml,
    readonly sourceClusterEndpoint?: string,
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
        const mskClusterAllTopicArn = `arn:${this.partition}:kafka:${this.region}:${this.account}:topic/${mskClusterName}/*`
        const mskTopicAdminPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [mskClusterAllTopicArn],
            actions: [
                "kafka-cluster:*"
            ]
        })
        const mskClusterAllGroupArn = `arn:${this.partition}:kafka:${this.region}:${this.account}:group/${mskClusterName}/*`
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
            resources: [`arn:${this.partition}:es:${this.region}:${this.account}:domain/*`]
        }))

        new StringParameter(this, 'SSMParameterOSIPipelineRoleArn', {
            description: 'OpenSearch Migration Parameter for OpenSearch Ingestion Pipeline Role ARN',
            parameterName: `/migration/${stage}/${deployId}/osiPipelineRoleArn`,
            stringValue: osiPipelineRole.roleArn
        });
        return osiPipelineRole.roleArn
    }

    createOpenSearchIngestionManagementPolicy(pipelineRoleArn: string): PolicyStatement[] {
        const allMigrationPipelineArn = `arn:${this.partition}:osis:${this.region}:${this.account}:pipeline/*`
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
        const configureLogGroupPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: ["*"],
            actions: [
                "logs:CreateLogDelivery",
                "logs:PutResourcePolicy",
                "logs:DescribeResourcePolicies",
                "logs:DescribeLogGroups"
            ]
        })
        return [osiManagementPolicy, passPipelineRolePolicy, configureLogGroupPolicy]
    }

    constructor(scope: Construct, id: string, props: MigrationConsoleProps) {
        super(scope, id, props)
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "trafficStreamSourceAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/trafficStreamSourceAccessSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "defaultDomainAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/osAccessSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "replayerOutputAccessSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/replayerOutputAccessSecurityGroupId`))
        ]

        let servicePortMappings: PortMapping[]|undefined
        let serviceDiscoveryPort: number|undefined
        let serviceDiscoveryEnabled = false
        let imageCommand: string[]|undefined

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
        const replayerOutputEFSArn = `arn:${this.partition}:elasticfilesystem:${this.region}:${this.account}:file-system/${volumeId}`
        const replayerOutputMountPolicy = new PolicyStatement( {
            effect: Effect.ALLOW,
            resources: [replayerOutputEFSArn],
            actions: [
                "elasticfilesystem:ClientMount",
                "elasticfilesystem:ClientWrite"
            ]
        })

        const ecsClusterArn = `arn:${this.partition}:ecs:${this.region}:${this.account}:service/migration-${props.stage}-ecs-cluster`
        const allReplayerServiceArn = `${ecsClusterArn}/migration-${props.stage}-traffic-replayer*`
        const reindexFromSnapshotServiceArn = `${ecsClusterArn}/migration-${props.stage}-reindex-from-snapshot`
        const ecsUpdateServicePolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [allReplayerServiceArn, reindexFromSnapshotServiceArn],
            actions: [
                "ecs:UpdateService",
                "ecs:DescribeServices"
            ]
        })

        const allClusterTasksArn = `arn:aws:ecs:${props.env?.region}:${props.env?.account}:task/migration-${props.stage}-ecs-cluster/*`
        const clusterTasksPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [allClusterTasksArn],
            actions: [
                "ecs:StopTask",
                "ecs:DescribeTasks"
            ]
        })

        const listTasksPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: ["*"],
            actions: [
                "ecs:ListTasks",
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
            resources: [`arn:${this.partition}:ssm:${this.region}:${this.account}:parameter/migration/${props.stage}/${props.defaultDeployId}/*`],
            actions: [
                "ssm:GetParameters"
            ]
        })

        // Allow Console to retrieve Cloudwatch Metrics
        const getMetricsPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: ["*"],
            actions: [
                "cloudwatch:ListMetrics",
                "cloudwatch:GetMetricData"
            ]
        })
        const sourceClusterEndpoint = props.sourceClusterEndpoint ?? `https://capture-proxy-es.migration.${props.stage}.local:9200`;

        // Upload the services.yaml file to Parameter Store
        let servicesYaml = props.servicesYaml
        servicesYaml.source_cluster = {
            'endpoint': sourceClusterEndpoint,
            // TODO: We're not currently supporting auth here, this may need to be handled on the migration console
            'no_auth': ''
        }
        // Create a new parameter in Parameter Store
        new StringParameter(this, 'SSMParameterServicesYamlFile', {
            parameterName: `/migration/${props.stage}/${props.defaultDeployId}/servicesYamlFile`,
            stringValue: servicesYaml.stringify(),
        });


        const environment: { [key: string]: string; } = {
            "MIGRATION_DOMAIN_ENDPOINT": osClusterEndpoint,
            // Temporary fix for source domain endpoint until we move to either alb or migration console yaml configuration
            "SOURCE_DOMAIN_ENDPOINT": sourceClusterEndpoint,
            "MIGRATION_KAFKA_BROKER_ENDPOINTS": brokerEndpoints,
            "MIGRATION_STAGE": props.stage,
            "MIGRATION_SOLUTION_VERSION": props.migrationsSolutionVersion,
            "MIGRATION_SERVICES_YAML_PARAMETER": `/migration/${props.stage}/${props.defaultDeployId}/servicesYamlFile`,
        }

        const openSearchPolicy = createOpenSearchIAMAccessPolicy(this.partition, this.region, this.account)
        const openSearchServerlessPolicy = createOpenSearchServerlessIAMAccessPolicy(this.partition, this.region, this.account)
        let servicePolicies = [replayerOutputMountPolicy, openSearchPolicy, openSearchServerlessPolicy, ecsUpdateServicePolicy, clusterTasksPolicy, listTasksPolicy, artifactS3PublishPolicy, describeVPCPolicy, getSSMParamsPolicy]
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
                    "ecs:RunTask",
                    "ecs:StopTask"
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

        if (props.migrationAPIEnabled) {
            servicePortMappings = [{
                name: "migration-console-connect",
                hostPort: 8000,
                containerPort: 8000,
                protocol: Protocol.TCP
            }]
            serviceDiscoveryPort = 8000
            serviceDiscoveryEnabled = true
            imageCommand = ['/bin/sh', '-c',
                '/root/loadServicesFromParameterStore.sh && python3 /root/console_api/manage.py runserver_plus 0.0.0.0:8000'
            ]
        }

        if (props.migrationConsoleEnableOSI) {
            const pipelineRoleArn = this.configureOpenSearchIngestionPipelineRole(props.stage, props.defaultDeployId)
            servicePolicies.push(...this.createOpenSearchIngestionManagementPolicy(pipelineRoleArn))
            const osiLogGroup = new LogGroup(this, 'OSILogGroup',  {
                retention: RetentionDays.ONE_MONTH,
                removalPolicy: RemovalPolicy.DESTROY,
                // Naming requirement from OSI
                logGroupName: `/aws/vendedlogs/osi-${props.stage}-${props.defaultDeployId}`
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
            portMappings: servicePortMappings,
            dockerImageCommand: imageCommand,
            serviceDiscoveryEnabled: serviceDiscoveryEnabled,
            serviceDiscoveryPort: serviceDiscoveryPort,
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