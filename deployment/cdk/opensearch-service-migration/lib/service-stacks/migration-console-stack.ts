import {StackPropsExt} from "../stack-composer";
import {IVpc, SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture, MountPoint, PortMapping, Protocol, Volume} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {Effect, PolicyStatement, Role, ServicePrincipal} from "aws-cdk-lib/aws-iam";
import {
    createMigrationStringParameter,
    createOpenSearchIAMAccessPolicy,
    createOpenSearchServerlessIAMAccessPolicy,
    getTargetPasswordAccessPolicy,
    getMigrationStringParameterValue,
    hashStringSHA256,
    MigrationSSMParameter
} from "../common-utilities";
import {StreamingSourceType} from "../streaming-source-type";
import {LogGroup, RetentionDays} from "aws-cdk-lib/aws-logs";
import {Fn, RemovalPolicy} from "aws-cdk-lib";
import {MetadataMigrationYaml, ServicesYaml} from "../migration-services-yaml";
import {ELBTargetGroup, MigrationServiceCore} from "./migration-service-core";
import { OtelCollectorSidecar } from "./migration-otel-collector-sidecar";
import { SharedLogFileSystem } from "../components/shared-log-file-system";

export interface MigrationConsoleProps extends StackPropsExt {
    readonly migrationsSolutionVersion: string,
    readonly vpc: IVpc,
    readonly streamingSourceType: StreamingSourceType,
    readonly fetchMigrationEnabled: boolean,
    readonly fargateCpuArch: CpuArchitecture,
    readonly migrationConsoleEnableOSI: boolean,
    readonly migrationAPIEnabled?: boolean,
    readonly migrationAPIAllowedHosts?: string,
    readonly targetGroups: ELBTargetGroup[],
    readonly servicesYaml: ServicesYaml,
    readonly otelCollectorEnabled?: boolean,
    readonly sourceClusterDisabled?: boolean,
}

export class MigrationConsoleStack extends MigrationServiceCore {

    getHostname(url: string): string {
        // https://alb.migration.dev.local:8000 -> alb.migration.dev.local
        return Fn.select(0, Fn.split(':', Fn.select(2, Fn.split('/', url))));
    }

    createMSKAdminIAMPolicies(stage: string, deployId: string): PolicyStatement[] {
        const mskClusterARN = getMigrationStringParameterValue(this, {
            parameter: MigrationSSMParameter.MSK_CLUSTER_ARN,
            stage,
            defaultDeployId: deployId,
        });
        const mskClusterName = getMigrationStringParameterValue(this, {
            parameter: MigrationSSMParameter.MSK_CLUSTER_NAME,
            stage,
            defaultDeployId: deployId,
        });
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

        createMigrationStringParameter(this, osiPipelineRole.roleArn, {
            parameter: MigrationSSMParameter.OSI_PIPELINE_ROLE_ARN,
            stage,
            defaultDeployId: deployId,
        });
        return osiPipelineRole.roleArn;
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
            { id: "serviceSG", param: MigrationSSMParameter.SERVICE_SECURITY_GROUP_ID },
            { id: "trafficStreamSourceAccessSG", param: MigrationSSMParameter.TRAFFIC_STREAM_SOURCE_ACCESS_SECURITY_GROUP_ID },
            { id: "defaultDomainAccessSG", param: MigrationSSMParameter.OS_ACCESS_SECURITY_GROUP_ID },
            { id: "sharedLogsAccessSG", param: MigrationSSMParameter.SHARED_LOGS_SECURITY_GROUP_ID }
        ].map(({ id, param }) =>
            SecurityGroup.fromSecurityGroupId(this, id, getMigrationStringParameterValue(this, {
                ...props,
                parameter: param,
            }))
        );

        let servicePortMappings: PortMapping[]|undefined
        let imageCommand: string[]|undefined

        const osClusterEndpoint = getMigrationStringParameterValue(this, {
            ...props,
            parameter: MigrationSSMParameter.OS_CLUSTER_ENDPOINT,
        });
        const sourceClusterEndpoint = props.sourceClusterDisabled ? null : getMigrationStringParameterValue(this, {
            ...props,
            parameter: MigrationSSMParameter.SOURCE_CLUSTER_ENDPOINT,
        });
        const brokerEndpoints = props.streamingSourceType != StreamingSourceType.DISABLED ?
            getMigrationStringParameterValue(this, {
                ...props,
                parameter: MigrationSSMParameter.KAFKA_BROKERS,
            }) : "";

        const sharedLogFileSystem = new SharedLogFileSystem(this, props.stage, props.defaultDeployId);
        

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

        const allClusterTasksArn = `arn:${this.partition}:ecs:${this.region}:${this.account}:task/migration-${props.stage}-ecs-cluster/*`
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

        const artifactS3Arn = getMigrationStringParameterValue(this, {
            ...props,
            parameter: MigrationSSMParameter.ARTIFACT_S3_ARN,
        });
        const artifactS3AnyObjectPath = `${artifactS3Arn}/*`;
        const artifactS3PublishPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: [artifactS3Arn, artifactS3AnyObjectPath],
            actions: [
                "s3:*"
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

        const getSecretsPolicy = props.servicesYaml.target_cluster.basic_auth?.password_from_secret_arn ? 
            getTargetPasswordAccessPolicy(props.servicesYaml.target_cluster.basic_auth.password_from_secret_arn) : null;

        // Upload the services.yaml file to Parameter Store
        let servicesYaml = props.servicesYaml
        if (!props.sourceClusterDisabled && sourceClusterEndpoint) {
            servicesYaml.source_cluster = {
                'endpoint': sourceClusterEndpoint,
                // TODO: We're not currently supporting auth here, this may need to be handled on the migration console
                'no_auth': ''
            }
        }
        servicesYaml.metadata_migration = new MetadataMigrationYaml();
        if (props.otelCollectorEnabled) {
            const otelSidecarEndpoint = OtelCollectorSidecar.getOtelLocalhostEndpoint();
            if (servicesYaml.metadata_migration) {
                servicesYaml.metadata_migration.otel_endpoint = otelSidecarEndpoint;
            }
            if (servicesYaml.snapshot) {
                servicesYaml.snapshot.otel_endpoint = otelSidecarEndpoint;
            }
        }

        const parameter = createMigrationStringParameter(this, servicesYaml.stringify(), {
            ...props,
            parameter: MigrationSSMParameter.SERVICES_YAML_FILE,
        });
        const environment: { [key: string]: string; } = {
            "MIGRATION_DOMAIN_ENDPOINT": osClusterEndpoint,
            "MIGRATION_KAFKA_BROKER_ENDPOINTS": brokerEndpoints,
            "MIGRATION_STAGE": props.stage,
            "MIGRATION_SOLUTION_VERSION": props.migrationsSolutionVersion,
            "MIGRATION_SERVICES_YAML_PARAMETER": parameter.parameterName,
            "MIGRATION_SERVICES_YAML_HASH": hashStringSHA256(servicesYaml.stringify()),
            "SHARED_LOGS_DIR_PATH": `${sharedLogFileSystem.mountPointPath}/migration-console-${props.defaultDeployId}`,
        }

        const openSearchPolicy = createOpenSearchIAMAccessPolicy(this.partition, this.region, this.account)
        const openSearchServerlessPolicy = createOpenSearchServerlessIAMAccessPolicy(this.partition, this.region, this.account)
        let servicePolicies = [sharedLogFileSystem.asPolicyStatement(), openSearchPolicy, openSearchServerlessPolicy, ecsUpdateServicePolicy, clusterTasksPolicy,
            listTasksPolicy, artifactS3PublishPolicy, describeVPCPolicy, getSSMParamsPolicy, getMetricsPolicy,
            ...(getSecretsPolicy ? [getSecretsPolicy] : []) // only add secrets policy if it's non-null
        ]
        if (props.streamingSourceType === StreamingSourceType.AWS_MSK) {
            const mskAdminPolicies = this.createMSKAdminIAMPolicies(props.stage, props.defaultDeployId)
            servicePolicies = servicePolicies.concat(mskAdminPolicies)
        }
        if (props.fetchMigrationEnabled) {
            environment["FETCH_MIGRATION_COMMAND"] = getMigrationStringParameterValue(this, {
                ...props,
                parameter: MigrationSSMParameter.FETCH_MIGRATION_COMMAND,
            });

            const fetchMigrationTaskDefArn = getMigrationStringParameterValue(this, {
                ...props,
                parameter: MigrationSSMParameter.FETCH_MIGRATION_TASK_DEF_ARN,
            });
            const fetchMigrationTaskRunPolicy = new PolicyStatement({
                effect: Effect.ALLOW,
                resources: [fetchMigrationTaskDefArn],
                actions: [
                    "ecs:RunTask",
                    "ecs:StopTask"
                ]
            })
            const fetchMigrationTaskRoleArn = getMigrationStringParameterValue(this, {
                ...props,
                parameter: MigrationSSMParameter.FETCH_MIGRATION_TASK_ROLE_ARN,
            });
            const fetchMigrationTaskExecRoleArn = getMigrationStringParameterValue(this, {
                ...props,
                parameter: MigrationSSMParameter.FETCH_MIGRATION_TASK_EXEC_ROLE_ARN,
            });
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
            imageCommand = ['/bin/sh', '-c',
                '/root/loadServicesFromParameterStore.sh && pipenv run python /root/console_api/manage.py runserver_plus 0.0.0.0:8000 --cert-file cert.crt'
            ]

            const defaultAllowedHosts = 'localhost'
            environment["API_ALLOWED_HOSTS"] = props.migrationAPIAllowedHosts ? `${defaultAllowedHosts},${props.migrationAPIAllowedHosts}` : defaultAllowedHosts
            const migrationApiUrl = getMigrationStringParameterValue(this, {
                ...props,
                parameter: MigrationSSMParameter.MIGRATION_API_URL
            });
            const migrationApiUrlAlias = getMigrationStringParameterValue(this, {
                ...props,
                parameter: MigrationSSMParameter.MIGRATION_API_URL_ALIAS
            });
            environment["API_ALLOWED_HOSTS"] += migrationApiUrl ? `,${this.getHostname(migrationApiUrl)}` : ""
            environment["API_ALLOWED_HOSTS"] += migrationApiUrlAlias ? `,${this.getHostname(migrationApiUrlAlias)}` : ""
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
            createMigrationStringParameter(this, osiLogGroup.logGroupName, {
                ...props,
                parameter: MigrationSSMParameter.OSI_PIPELINE_LOG_GROUP_NAME,
            });
        }
        this.createService({
            serviceName: "migration-console",
            dockerDirectoryPath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/src/main/docker/migrationConsole"),
            securityGroups: securityGroups,
            portMappings: servicePortMappings,
            dockerImageCommand: imageCommand,
            volumes: [sharedLogFileSystem.asVolume()],
            mountPoints: [sharedLogFileSystem.asMountPoint()],
            environment: environment,
            taskRolePolicies: servicePolicies,
            cpuArchitecture: props.fargateCpuArch,
            taskCpuUnits: 1024,
            taskMemoryLimitMiB: 2048,
            ...props
        });

    }

}
