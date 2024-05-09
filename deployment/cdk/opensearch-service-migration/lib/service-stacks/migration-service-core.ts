import {StackPropsExt} from "../stack-composer";
import {ISecurityGroup, IVpc, SubnetType} from "aws-cdk-lib/aws-ec2";
import {
    CfnService as FargateCfnService, CloudMapOptions,
    Cluster,
    ContainerImage, CpuArchitecture,
    FargateService,
    FargateTaskDefinition,
    LogDrivers,
    MountPoint,
    PortMapping,
    ServiceConnectService,
    Ulimit,
    OperatingSystemFamily,
    Volume,
    AwsLogDriverMode,
    ContainerDependencyCondition
} from "aws-cdk-lib/aws-ecs";
import {DockerImageAsset} from "aws-cdk-lib/aws-ecr-assets";
import {Duration, RemovalPolicy, Stack} from "aws-cdk-lib";
import {LogGroup, RetentionDays} from "aws-cdk-lib/aws-logs";
import {PolicyStatement} from "aws-cdk-lib/aws-iam";
import {CfnService as DiscoveryCfnService, PrivateDnsNamespace} from "aws-cdk-lib/aws-servicediscovery";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {createDefaultECSTaskRole} from "../common-utilities";


export interface MigrationServiceCoreProps extends StackPropsExt {
    readonly serviceName: string,
    readonly vpc: IVpc,
    readonly securityGroups: ISecurityGroup[],
    readonly cpuArchitecture: CpuArchitecture,
    readonly dockerFilePath?: string,
    readonly dockerDirectoryPath?: string,
    readonly dockerImageRegistryName?: string,
    readonly dockerImageCommand?: string[],
    readonly taskRolePolicies?: PolicyStatement[],
    readonly mountPoints?: MountPoint[],
    readonly volumes?: Volume[],
    readonly portMappings?: PortMapping[],
    readonly environment?: {
        [key: string]: string;
    },
    readonly serviceConnectServices?: ServiceConnectService[],
    readonly serviceDiscoveryEnabled?: boolean,
    readonly serviceDiscoveryPort?: number,
    readonly taskCpuUnits?: number,
    readonly taskMemoryLimitMiB?: number,
    readonly taskInstanceCount?: number,
    readonly ulimits?: Ulimit[],
    readonly maxUptime?: Duration
}

export class MigrationServiceCore extends Stack {

    // Use CDK escape hatch to modify the underlying CFN for the generated AWS::ServiceDiscovery::Service to allow
    // multiple DnsRecords. GitHub issue can be found here: https://github.com/aws/aws-cdk/issues/18894
    addServiceDiscoveryRecords(fargateService: FargateService, serviceDiscoveryPort: number|undefined) {
        const multipleDnsRecords = {
            DnsRecords: [
                {
                    TTL: 10,
                    Type: "A"
                },
                {
                    TTL: 10,
                    Type: "SRV"
                }
            ]
        }
        const cloudMapCfn = fargateService.node.findChild("CloudmapService")
        const cloudMapServiceCfn = cloudMapCfn.node.defaultChild as DiscoveryCfnService
        cloudMapServiceCfn.addPropertyOverride("DnsConfig", multipleDnsRecords)

        if (serviceDiscoveryPort) {
            const fargateCfn = fargateService.node.defaultChild as FargateCfnService
            fargateCfn.addPropertyOverride("ServiceRegistries.0.Port", serviceDiscoveryPort)
        }
    }

    createService(props: MigrationServiceCoreProps) {
        if ((!props.dockerDirectoryPath && !props.dockerImageRegistryName) || (props.dockerDirectoryPath && props.dockerImageRegistryName)) {
            throw new Error(`Exactly one option [dockerDirectoryPath, dockerImageRegistryName] is required to create the "${props.serviceName}" service`)
        }

        const ecsCluster = Cluster.fromClusterAttributes(this, 'ecsCluster', {
            clusterName: `migration-${props.stage}-ecs-cluster`,
            vpc: props.vpc
        })

        const serviceTaskRole = createDefaultECSTaskRole(this, props.serviceName)
        props.taskRolePolicies?.forEach(policy => serviceTaskRole.addToPolicy(policy))

        const serviceTaskDef = new FargateTaskDefinition(this, "ServiceTaskDef", {
            ephemeralStorageGiB: 75,
            runtimePlatform: {
                operatingSystemFamily: OperatingSystemFamily.LINUX,
                cpuArchitecture: props.cpuArchitecture
            },
            family: `migration-${props.stage}-${props.serviceName}`,
            memoryLimitMiB: props.taskMemoryLimitMiB ? props.taskMemoryLimitMiB : 1024,
            cpu: props.taskCpuUnits ? props.taskCpuUnits : 256,
            taskRole: serviceTaskRole
        });
        if (props.volumes) {
            props.volumes.forEach(vol => serviceTaskDef.addVolume(vol))
        }

        let serviceImage
        if (props.dockerDirectoryPath) {
            serviceImage = ContainerImage.fromDockerImageAsset(new DockerImageAsset(this, "ServiceImage", {
                directory: props.dockerDirectoryPath,
                // File path relative to above directory path
                file: props.dockerFilePath
            }))
        }
        else {
            // @ts-ignore
            serviceImage = ContainerImage.fromRegistry(props.dockerImageRegistryName)
        }

        const serviceLogGroup = new LogGroup(this, 'ServiceLogGroup',  {
            retention: RetentionDays.ONE_MONTH,
            removalPolicy: RemovalPolicy.DESTROY,
            logGroupName: `/migration/${props.stage}/${props.defaultDeployId}/${props.serviceName}`
        });

        const serviceContainer = serviceTaskDef.addContainer("ServiceContainer", {
            image: serviceImage,
            containerName: props.serviceName,
            command: props.dockerImageCommand,
            environment: props.environment,
            portMappings: props.portMappings,
            logging: LogDrivers.awsLogs({
                streamPrefix: `${props.serviceName}-logs`,
                logGroup: serviceLogGroup,
                // E.g. "[INFO ] 2024-12-31 23:59:59..."
                // and  "[ERROR] 2024-12-31 23:59:59..."
                // and  "2024-12-31 23:59:59..."
                // and  "2024-12-31T23:59:59..."
                multilinePattern: "^(\\[[A-Z ]{1,5}\\] )?\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}",
                // Defer buffering behavior to log4j2 for greater flexibility
                mode: AwsLogDriverMode.BLOCKING,
            }),
            ulimits: props.ulimits
        });
        if (props.mountPoints) {
            serviceContainer.addMountPoints(...props.mountPoints)
        }

        if (props.maxUptime) {
            let maxUptimeSeconds = Math.max(props.maxUptime.toSeconds(), Duration.minutes(5).toSeconds());

            // This sets the minimum time that a task should be running before considering it healthy.
            // This is needed because we don't have health checks configured for our service containers.
            // We can reduce it lower than 30 seconds, and possibly remove it, but then we increase the delay
            // between the current task being shutdown and the new task being ready to take work. This means
            // health checks for this container will fail initially and startPeriod will stop ECS from treating
            // failing health checks as unhealthy during this period.
            let startupPeriodSeconds = 30;
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


        let cloudMapOptions: CloudMapOptions|undefined = undefined
        if (props.serviceDiscoveryEnabled) {
            const namespaceId = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/cloudMapNamespaceId`)
            const namespace = PrivateDnsNamespace.fromPrivateDnsNamespaceAttributes(this, "PrivateDNSNamespace", {
                namespaceName: `migration.${props.stage}.local`,
                namespaceId: namespaceId,
                namespaceArn: `arn:aws:servicediscovery:${this.region}:${this.account}:namespace/${namespaceId}`
            })
            cloudMapOptions = {
                name: props.serviceName,
                cloudMapNamespace: namespace,
            }
        }

        const fargateService = new FargateService(this, "ServiceFargateService", {
            serviceName: `migration-${props.stage}-${props.serviceName}`,
            cluster: ecsCluster,
            taskDefinition: serviceTaskDef,
            assignPublicIp: true,
            desiredCount: props.taskInstanceCount,
            enableExecuteCommand: true,
            securityGroups: props.securityGroups,
            vpcSubnets: props.vpc.selectSubnets({subnetType: SubnetType.PRIVATE_WITH_EGRESS}),
            serviceConnectConfiguration: {
                namespace: `migration.${props.stage}.local`,
                services: props.serviceConnectServices ? props.serviceConnectServices : undefined,
                logDriver: LogDrivers.awsLogs({
                    streamPrefix: "service-connect-logs",
                    logGroup: serviceLogGroup
                })
            },
            cloudMapOptions: cloudMapOptions
        });

        if (props.serviceDiscoveryEnabled) {
            this.addServiceDiscoveryRecords(fargateService, props.serviceDiscoveryPort)
        }
    }

}