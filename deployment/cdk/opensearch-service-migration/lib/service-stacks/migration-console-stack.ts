import {StackPropsExt} from "../stack-composer";
import {VpcDetails} from "../network-stack";
import {SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture, PortMapping, Protocol} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {Effect, PolicyStatement, Role, ServicePrincipal} from "aws-cdk-lib/aws-iam";
import {
    createMigrationStringParameter,
    createOpenSearchIAMAccessPolicy,
    createOpenSearchServerlessIAMAccessPolicy,
    getSecretAccessPolicy,
    getMigrationStringParameterValue,
    hashStringSHA256,
    MigrationSSMParameter,
    createSnapshotOnAOSRole
} from "../common-utilities";
import {StreamingSourceType} from "../streaming-source-type";
import {LogGroup, RetentionDays} from "aws-cdk-lib/aws-logs";
import {Fn, RemovalPolicy} from "aws-cdk-lib";
import {ClusterYaml, MetadataMigrationYaml, ServicesYaml} from "../migration-services-yaml";
import {ELBTargetGroup, MigrationServiceCore} from "./migration-service-core";
import { OtelCollectorSidecar } from "./migration-otel-collector-sidecar";
import { SharedLogFileSystem } from "../components/shared-log-file-system";

export interface MigrationConsoleProps extends StackPropsExt {
    readonly migrationsSolutionVersion: string,
    readonly vpcDetails: VpcDetails,
    readonly streamingSourceType: StreamingSourceType,
    readonly fargateCpuArch: CpuArchitecture,
    readonly migrationConsoleEnableOSI: boolean,
    readonly migrationAPIEnabled?: boolean,
    readonly migrationAPIAllowedHosts?: string,
    readonly targetGroups?: ELBTargetGroup[],
    readonly servicesYaml: ServicesYaml,
    readonly otelCollectorEnabled?: boolean,
    readonly sourceCluster?: ClusterYaml,
    readonly managedServiceSourceSnapshotEnabled?: boolean
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

        const securityGroups = [
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
        const s3AccessPolicy = new PolicyStatement({
            effect: Effect.ALLOW,
            resources: ["*"],
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

        const getTargetSecretsPolicy = props.servicesYaml.target_cluster.auth.basicAuth?.password_from_secret_arn ?
            getSecretAccessPolicy(props.servicesYaml.target_cluster.auth.basicAuth?.password_from_secret_arn) : null;

        const getSourceSecretsPolicy = props.sourceCluster?.auth.basicAuth?.password_from_secret_arn ?
            getSecretAccessPolicy(props.sourceCluster?.auth.basicAuth?.password_from_secret_arn) : null;

        // Upload the services.yaml file to Parameter Store
        const servicesYaml = props.servicesYaml
        servicesYaml.source_cluster = props.sourceCluster
        servicesYaml.metadata_migration = new MetadataMigrationYaml();
        servicesYaml.metadata_migration.source_cluster_version = props.sourceCluster?.version
        if (props.otelCollectorEnabled) {
            const otelSidecarEndpoint = OtelCollectorSidecar.getOtelLocalhostEndpoint();
            if (servicesYaml.metadata_migration) {
                servicesYaml.metadata_migration.otel_endpoint = otelSidecarEndpoint;
            }
            if (servicesYaml.snapshot) {
                servicesYaml.snapshot.otel_endpoint = otelSidecarEndpoint;
            }
            if (servicesYaml.metrics_source?.cloudwatch !== undefined) {
                servicesYaml.metrics_source.cloudwatch = {
                    ...servicesYaml.metrics_source.cloudwatch,
                    qualifier : props.stage };
            }
        }

        const serviceTaskRole = new Role(this, 'MigrationServiceTaskRole', {
            assumedBy: new ServicePrincipal('ecs-tasks.amazonaws.com'),
            description: 'Role for Migration Console ECS Tasks',
        });

        const openSearchPolicy = createOpenSearchIAMAccessPolicy(this.partition, this.region, this.account)
        const openSearchServerlessPolicy = createOpenSearchServerlessIAMAccessPolicy(this.partition, this.region, this.account)
        let servicePolicies = [sharedLogFileSystem.asPolicyStatement(), openSearchPolicy, openSearchServerlessPolicy, ecsUpdateServicePolicy, clusterTasksPolicy,
            listTasksPolicy, s3AccessPolicy, describeVPCPolicy, getSSMParamsPolicy, getMetricsPolicy,
            // only add secrets policies if they're non-null
            ...(getTargetSecretsPolicy ? [getTargetSecretsPolicy] : []),
            ...(getSourceSecretsPolicy ? [getSourceSecretsPolicy] : [])
        ]

        if (props.streamingSourceType === StreamingSourceType.AWS_MSK) {
            const mskAdminPolicies = this.createMSKAdminIAMPolicies(props.stage, props.defaultDeployId)
            servicePolicies = servicePolicies.concat(mskAdminPolicies)
        }

        if (props.managedServiceSourceSnapshotEnabled && servicesYaml.snapshot?.s3) {
            servicesYaml.snapshot.s3.role =
                createSnapshotOnAOSRole(this, artifactS3Arn, serviceTaskRole.roleArn,
                    this.region, props.stage, props.defaultDeployId)
                    .roleArn;
        }

        const parameter = createMigrationStringParameter(this, servicesYaml.stringify(), {
            ...props,
            parameter: MigrationSSMParameter.SERVICES_YAML_FILE,
        });
        const environment: Record<string, string> = {
            "MIGRATION_DOMAIN_ENDPOINT": osClusterEndpoint,
            "MIGRATION_KAFKA_BROKER_ENDPOINTS": brokerEndpoints,
            "MIGRATION_STAGE": props.stage,
            "MIGRATION_SOLUTION_VERSION": props.migrationsSolutionVersion,
            "MIGRATION_SERVICES_YAML_PARAMETER": parameter.parameterName,
            "MIGRATION_SERVICES_YAML_HASH": hashStringSHA256(servicesYaml.stringify()),
            "SHARED_LOGS_DIR_PATH": `${sharedLogFileSystem.mountPointPath}/migration-console-${props.defaultDeployId}`,
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
            dockerImageName: "migrations/migration_console:latest",
            securityGroups: securityGroups,
            portMappings: servicePortMappings,
            dockerImageCommand: imageCommand,
            volumes: [sharedLogFileSystem.asVolume()],
            mountPoints: [sharedLogFileSystem.asMountPoint()],
            environment: environment,
            taskRole: serviceTaskRole,
            taskRolePolicies: servicePolicies,
            cpuArchitecture: props.fargateCpuArch,
            taskCpuUnits: 2048,
            taskMemoryLimitMiB: 4096,
            ...props
        });
    }

}
