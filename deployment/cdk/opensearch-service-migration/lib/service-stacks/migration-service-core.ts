import {StackPropsExt} from "../stack-composer";
import {ISecurityGroup, IVpc, SubnetType} from "aws-cdk-lib/aws-ec2";
import {
    CfnService as FargateCfnService,
    Cluster,
    ContainerImage,
    FargateService,
    FargateTaskDefinition,
    LogDrivers,
    MountPoint,
    PortMapping, Ulimit,
    Volume
} from "aws-cdk-lib/aws-ecs";
import {DockerImageAsset} from "aws-cdk-lib/aws-ecr-assets";
import {RemovalPolicy, Stack} from "aws-cdk-lib";
import {LogGroup, RetentionDays} from "aws-cdk-lib/aws-logs";
import {PolicyStatement} from "aws-cdk-lib/aws-iam";
import {CloudMapOptions, ServiceConnectService} from "aws-cdk-lib/aws-ecs/lib/base/base-service";
import {CfnService as DiscoveryCfnService, PrivateDnsNamespace} from "aws-cdk-lib/aws-servicediscovery";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {createDefaultECSTaskRole} from "../common-utilities";


export interface MigrationServiceCoreProps extends StackPropsExt {
    readonly serviceName: string,
    readonly vpc: IVpc,
    readonly securityGroups: ISecurityGroup[],
    readonly dockerFilePath?: string,
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
    readonly ulimits?: Ulimit[]
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
        if ((!props.dockerFilePath && !props.dockerImageRegistryName) || (props.dockerFilePath && props.dockerImageRegistryName)) {
            throw new Error(`Exactly one option [dockerFilePath, dockerImageRegistryName] is required to create the "${props.serviceName}" service`)
        }

        const ecsCluster = Cluster.fromClusterAttributes(this, 'ecsCluster', {
            clusterName: `migration-${props.stage}-ecs-cluster`,
            vpc: props.vpc
        })

        const serviceTaskRole = createDefaultECSTaskRole(this, props.serviceName)
        props.taskRolePolicies?.forEach(policy => serviceTaskRole.addToPolicy(policy))

        const serviceTaskDef = new FargateTaskDefinition(this, "ServiceTaskDef", {
            family: `migration-${props.stage}-${props.serviceName}`,
            memoryLimitMiB: props.taskMemoryLimitMiB ? props.taskMemoryLimitMiB : 1024,
            cpu: props.taskCpuUnits ? props.taskCpuUnits : 256,
            taskRole: serviceTaskRole
        });
        if (props.volumes) {
            props.volumes.forEach(vol => serviceTaskDef.addVolume(vol))
        }

        let serviceImage
        if (props.dockerFilePath) {
            serviceImage = ContainerImage.fromDockerImageAsset(new DockerImageAsset(this, "ServiceImage", {
                directory: props.dockerFilePath
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
                logGroup: serviceLogGroup
            }),
            ulimits: props.ulimits
        });
        if (props.mountPoints) {
            serviceContainer.addMountPoints(...props.mountPoints)
        }

        let cloudMapOptions: CloudMapOptions|undefined = undefined
        if (props.serviceDiscoveryEnabled) {
            const namespaceId = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/cloudMapNamespaceId`)
            const namespace = PrivateDnsNamespace.fromPrivateDnsNamespaceAttributes(this, "PrivateDNSNamespace", {
                namespaceName: `migration.${props.stage}.local`,
                namespaceId: namespaceId,
                namespaceArn: `arn:aws:servicediscovery:${props.env?.region}:${props.env?.account}:namespace/${namespaceId}`
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
            // This should be confirmed to be a requirement for Service Connect communication, otherwise be Private
            vpcSubnets: props.vpc.selectSubnets({subnetType: SubnetType.PUBLIC}),
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