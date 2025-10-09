import {StackPropsExt} from "../stack-composer";
import {VpcDetails} from "../network-stack";
import {ISecurityGroup} from "aws-cdk-lib/aws-ec2";
import {
    Cluster,
    ContainerImage, CpuArchitecture,
    FargateService,
    FargateTaskDefinition,
    LogDrivers,
    MountPoint,
    PortMapping,
    Ulimit,
    OperatingSystemFamily,
    Volume,
    AwsLogDriverMode,
    ContainerDependencyCondition,
    ServiceManagedVolume,
    Secret as EcsSecret
} from "aws-cdk-lib/aws-ecs";
import {Duration, RemovalPolicy, Stack} from "aws-cdk-lib";
import {LogGroup, RetentionDays} from "aws-cdk-lib/aws-logs";
import {PolicyStatement, Role} from "aws-cdk-lib/aws-iam";
import {createDefaultECSTaskRole, makeLocalAssetContainerImage} from "../common-utilities";
import {OtelCollectorSidecar} from "./migration-otel-collector-sidecar";
import { IApplicationTargetGroup, INetworkTargetGroup } from "aws-cdk-lib/aws-elasticloadbalancingv2";


export interface MigrationServiceCoreProps extends StackPropsExt {
    readonly serviceName: string,
    readonly vpcDetails: VpcDetails,
    readonly securityGroups: ISecurityGroup[],
    readonly cpuArchitecture: CpuArchitecture,
    readonly dockerImageName: string,
    readonly dockerImageCommand?: string[],
    readonly taskRole?: Role,
    readonly taskRolePolicies?: PolicyStatement[],
    readonly mountPoints?: MountPoint[],
    readonly volumes?: Volume[],
    readonly portMappings?: PortMapping[],
    readonly environment?: Record<string, string>,
    readonly taskCpuUnits?: number,
    readonly taskMemoryLimitMiB?: number,
    readonly taskInstanceCount?: number,
    readonly ulimits?: Ulimit[],
    readonly maxUptime?: Duration,
    readonly otelCollectorEnabled?: boolean,
    readonly targetGroups?: ELBTargetGroup[],
    readonly ephemeralStorageGiB?: number,
    readonly secrets?: Record<string, EcsSecret>
}

export type ELBTargetGroup = IApplicationTargetGroup | INetworkTargetGroup;

export class MigrationServiceCore extends Stack {
    serviceTaskRole: Role;

    createService(props: MigrationServiceCoreProps) {
        const ecsCluster = Cluster.fromClusterAttributes(this, 'ecsCluster', {
            clusterName: `migration-${props.stage}-ecs-cluster`,
            vpc: props.vpcDetails.vpc
        })

        this.serviceTaskRole = props.taskRole ?? createDefaultECSTaskRole(this, props.serviceName, this.region, props.stage)
        props.taskRolePolicies?.forEach(policy => this.serviceTaskRole.addToPolicy(policy))

        const serviceTaskDef = new FargateTaskDefinition(this, "ServiceTaskDef", {
            ephemeralStorageGiB: Math.max(props.ephemeralStorageGiB ?? 75, 21), // valid values 21 - 200
            runtimePlatform: {
                operatingSystemFamily: OperatingSystemFamily.LINUX,
                cpuArchitecture: props.cpuArchitecture
            },
            family: `migration-${props.stage}-${props.serviceName}`,
            memoryLimitMiB: props.taskMemoryLimitMiB ?? 1024,
            cpu: props.taskCpuUnits ?? 256,
            taskRole: this.serviceTaskRole
        });
        if (props.volumes) {
            props.volumes.forEach(vol => serviceTaskDef.addVolume(vol))
        }

        const serviceImage = makeLocalAssetContainerImage(this, props.dockerImageName)

        const serviceLogGroup = new LogGroup(this, 'ServiceLogGroup',  {
            retention: RetentionDays.ONE_MONTH,
            removalPolicy: RemovalPolicy.DESTROY,
            logGroupName: `/migration/${props.stage}/${props.defaultDeployId}/${props.serviceName}`
        });

        const multilineRe = /^(\[[A-Z ]{1,5}\] )?\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}/;
        const serviceContainer = serviceTaskDef.addContainer("ServiceContainer", {
            image: serviceImage,
            containerName: props.serviceName,
            command: props.dockerImageCommand,
            environment: props.environment,
            secrets: props.secrets,
            portMappings: props.portMappings,
            logging: LogDrivers.awsLogs({
                streamPrefix: `${props.serviceName}-logs`,
                logGroup: serviceLogGroup,
                // E.g. "[INFO ] 2024-12-31 23:59:59..."
                // and  "[ERROR] 2024-12-31 23:59:59..."
                // and  "2024-12-31 23:59:59..."
                // and  "2024-12-31T23:59:59..."
                multilinePattern: multilineRe.source,
                // Defer buffering behavior to log4j2 for greater flexibility
                mode: AwsLogDriverMode.BLOCKING,
            }),
            ulimits: props.ulimits
        });
        if (props.mountPoints) {
            serviceContainer.addMountPoints(...props.mountPoints)
        }

        if (props.maxUptime) {
            const maxUptimeSeconds = Math.max(props.maxUptime.toSeconds(), Duration.minutes(5).toSeconds());

            // This sets the minimum time that a task should be running before considering it healthy.
            // This is needed because we don't have health checks configured for our service containers.
            // We can reduce it lower than 30 seconds, and possibly remove it, but then we increase the delay
            // between the current task being shutdown and the new task being ready to take work. This means
            // health checks for this container will fail initially and startPeriod will stop ECS from treating
            // failing health checks as unhealthy during this period.
            const startupPeriodSeconds = 30;
            // Add a separate container to monitor and fail healthcheck after a given maxUptime
            const maxUptimeContainer = serviceTaskDef.addContainer("MaxUptimeContainer", {
                image: ContainerImage.fromRegistry("public.ecr.aws/amazonlinux/amazonlinux:2023-minimal"),
                memoryLimitMiB: 64,
                entryPoint: [
                    "/bin/sh",
                    "-c",
                    "sleep infinity"
                ],
                essential: true,
                // Every time this healthcheck is called, it verifies container uptime is within given
                // bounds of startupPeriodSeconds and maxUptimeSeconds otherwise reporting unhealthy which
                // signals ECS to start up a replacement task and kill this one once the replacement is healthy
                healthCheck: {
                    command: [
                        "CMD-SHELL",
                        "UPTIME=$(awk '{print int($1)}' /proc/uptime); " +
                        `test $UPTIME -gt ${startupPeriodSeconds} && ` +
                        `test $UPTIME -lt ${maxUptimeSeconds}`
                    ],
                    timeout: Duration.seconds(2),
                    retries: 1,
                    startPeriod: Duration.seconds(startupPeriodSeconds * 2)
                }
            });
            // Dependency on maxUptimeContainer to wait until serviceContainer is started
            // Cannot depend on Healthy given serviceContainer does not have a healthcheck configured.
            maxUptimeContainer.addContainerDependencies({
                container: serviceContainer,
                condition: ContainerDependencyCondition.START,
            });
        }

        if (props.otelCollectorEnabled) {
            OtelCollectorSidecar.addOtelCollectorContainer(serviceTaskDef, serviceLogGroup.logGroupName, props.stage);
        }

        const fargateService = new FargateService(this, "ServiceFargateService", {
            serviceName: `migration-${props.stage}-${props.serviceName}`,
            cluster: ecsCluster,
            taskDefinition: serviceTaskDef,
            assignPublicIp: true,
            desiredCount: props.taskInstanceCount,
            enableExecuteCommand: true,
            securityGroups: props.securityGroups,
            vpcSubnets: props.vpcDetails.subnetSelection,
        });

        // Add any ServiceManagedVolumes to the service, if they exist
        if (props.volumes) {
            props.volumes.filter(vol => vol instanceof ServiceManagedVolume).forEach(vol => fargateService.addVolume(vol));
        }

        if (props.targetGroups) {
            props.targetGroups.filter(tg => tg !== undefined).forEach(tg => tg.addTarget(fargateService));
        }

    }
}
