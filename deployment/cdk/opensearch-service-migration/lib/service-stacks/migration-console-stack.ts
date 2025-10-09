import {StackPropsExt} from "../stack-composer";
import {VpcDetails} from "../network-stack";
import {SecurityGroup} from "aws-cdk-lib/aws-ec2";
import {CpuArchitecture} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";
import {
    createMigrationStringParameter,
    createAllAccessOpenSearchIAMAccessPolicy,
    createAllAccessOpenSearchServerlessIAMAccessPolicy,
    getSecretAccessPolicy,
    getMigrationStringParameterValue,
    hashStringSHA256,
    MigrationSSMParameter,
    createSnapshotOnAOSRole, createECSTaskRole
} from "../common-utilities";
import {StreamingSourceType} from "../streaming-source-type";
import {Fn} from "aws-cdk-lib";
import {MetadataMigrationYaml, ServicesYaml} from "../migration-services-yaml";
import {ELBTargetGroup, MigrationServiceCore} from "./migration-service-core";
import { OtelCollectorSidecar } from "./migration-otel-collector-sidecar";
import { SharedLogFileSystem } from "../components/shared-log-file-system";

export interface MigrationConsoleProps extends StackPropsExt {
    readonly migrationsSolutionVersion: string,
    readonly vpcDetails: VpcDetails,
    readonly streamingSourceType: StreamingSourceType,
    readonly fargateCpuArch: CpuArchitecture,
    readonly targetGroups?: ELBTargetGroup[],
    readonly servicesYaml: ServicesYaml,
    readonly sourceClusterVersion?: string,
    readonly otelCollectorEnabled?: boolean,
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

        const serviceName = "migration-console"

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

        const servicesYaml = props.servicesYaml
        const secretPolicies = []
        if (servicesYaml.target_cluster.auth.basicAuth?.user_secret_arn) {
            secretPolicies.push(getSecretAccessPolicy(servicesYaml.target_cluster.auth.basicAuth.user_secret_arn))
        }
        if (servicesYaml.source_cluster?.auth.basicAuth?.user_secret_arn) {
            secretPolicies.push(getSecretAccessPolicy(servicesYaml.source_cluster.auth.basicAuth.user_secret_arn))
        }

        // Upload the services.yaml file to Parameter Store
        servicesYaml.metadata_migration = new MetadataMigrationYaml();
        servicesYaml.metadata_migration.source_cluster_version = props.sourceClusterVersion
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

        const serviceTaskRole = createECSTaskRole(this, serviceName, this.region, props.stage)

        const openSearchPolicy = createAllAccessOpenSearchIAMAccessPolicy()
        const openSearchServerlessPolicy = createAllAccessOpenSearchServerlessIAMAccessPolicy()
        let servicePolicies = [sharedLogFileSystem.asPolicyStatement(), openSearchPolicy, openSearchServerlessPolicy, ecsUpdateServicePolicy, clusterTasksPolicy,
            listTasksPolicy, s3AccessPolicy, describeVPCPolicy, getSSMParamsPolicy, getMetricsPolicy,
            ...secretPolicies
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

        this.createService({
            serviceName: serviceName,
            dockerImageName: "migrations/migration_console:latest",
            securityGroups: securityGroups,
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
